/*
 * Copyright (c) 2025 SUSE LLC
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package com.redhat.rhn.manager.kickstart;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import com.redhat.rhn.common.conf.ConfigDefaults;
import com.redhat.rhn.common.util.FileUtils;
import com.redhat.rhn.domain.channel.Channel;
import com.redhat.rhn.domain.channel.ChannelFactory;
import com.redhat.rhn.domain.kickstart.KickstartData;
import com.redhat.rhn.domain.kickstart.KickstartFactory;
import com.redhat.rhn.domain.kickstart.KickstartRawData;
import com.redhat.rhn.domain.kickstart.KickstartSession;
import com.redhat.rhn.domain.kickstart.KickstartableTree;
import com.redhat.rhn.domain.token.ActivationKey;
import com.redhat.rhn.domain.token.ActivationKeyFactory;
import com.redhat.rhn.domain.token.Token;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * Renders Agama JSON autoinstallation profiles.
 *
 * Agama profiles are served directly by Uyuni (bypassing Cobbler's Cheetah engine) with
 * @@VARIABLE@@ substitution and automatic injection of the registration snippet into
 * scripts.init[]. The @@VARIABLE@@ syntax is JSON-safe (no conflict with Cheetah's
 * $var / #directive syntax or JSON's own special characters).
 *
 * <p>Supported template variables — analogous to the Cobbler variables available in AutoYaST
 * profiles:
 * <pre>
 *   @@UYUNI_SERVER@@    server FQDN           ($redhat_management_server in AutoYaST)
 *   @@DISTROTREE@@      distribution label    ($distrotree in AutoYaST)
 *   @@MEDIA_PATH@@      base media path       ($media_path in AutoYaST)
 *   @@ACTIVATION_KEY@@  activation key(s)     ($redhat_management_key in AutoYaST)
 *   @@ORG_ID@@          organisation ID
 *   @@CHILD_REPOS@@     JSON array of child-channel repos for software.extraRepositories
 *                        (equivalent to $SNIPPET('spacewalk/autoyast_channels'))
 * </pre>
 *
 * <p>Registration is injected from the {@value #REGISTRATION_SNIPPET_NAME} file in the
 * Cobbler snippets directory, making it customisable the same way AutoYaST snippets are.
 */
public class AgamaProfileRenderer {

    private static final Logger LOG = LogManager.getLogger(AgamaProfileRenderer.class);

    /** Uyuni/SUSE Manager server FQDN — equivalent to Cobbler's $redhat_management_server */
    public static final String VAR_SERVER = "@@UYUNI_SERVER@@";

    /** Distribution tree label — equivalent to Cobbler's $distrotree */
    public static final String VAR_DISTROTREE = "@@DISTROTREE@@";

    /**
     * Base media path for the distribution tree (e.g. {@code /ks/dist/sles16-x86_64})
     * — equivalent to Cobbler's $media_path
     */
    public static final String VAR_MEDIA_PATH = "@@MEDIA_PATH@@";

    /**
     * Activation key(s) comma-separated — equivalent to Cobbler's $redhat_management_key.
     * The first entry is the management key; subsequent entries are additional tokens.
     */
    public static final String VAR_ACTIVATION_KEY = "@@ACTIVATION_KEY@@";

    /** Organisation ID of the profile's owner */
    public static final String VAR_ORG_ID = "@@ORG_ID@@";

    /**
     * JSON array of child-channel repository objects suitable for Agama's
     * {@code software.extraRepositories} field — equivalent to
     * {@code $SNIPPET('spacewalk/autoyast_channels')}.
     *
     * <p>Each element has the shape:
     * <pre>{"url": "http://SERVER/ks/dist/child/CHANNEL/DISTROTREE", "alias": "CHANNEL"}</pre>
     */
    public static final String VAR_CHILD_REPOS = "@@CHILD_REPOS@@";

    /** Name of the registration snippet file under the Cobbler snippets directory */
    static final String REGISTRATION_SNIPPET_NAME = "agama_register_using_bootstrap";

    /** {@code name} field value used to identify the injected registration init-script */
    static final String REGISTRATION_SCRIPT_ENTRY_NAME = "register-to-uyuni.sh";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Render an Agama JSON profile with variable substitution and registration injection.
     *
     * @param ksdata the kickstart profile (must be a {@link KickstartRawData} backed by
     *               an Agama distribution tree)
     * @return the rendered JSON as a string, ready to be served to the installer
     */
    public String render(KickstartData ksdata) {
        String rawJson = getRawJson(ksdata);
        String processed = substituteVariables(rawJson, ksdata);
        processed = injectRegistrationIfMissing(processed, ksdata);
        return processed;
    }

    // -------------------------------------------------------------------------
    // Profile content
    // -------------------------------------------------------------------------

    private String getRawJson(KickstartData ksdata) {
        if (ksdata instanceof KickstartRawData rawData) {
            return rawData.getData();
        }
        LOG.warn("Agama profile {} is not a KickstartRawData — returning empty profile",
                ksdata.getLabel());
        return "{}";
    }

    // -------------------------------------------------------------------------
    // Variable substitution
    // -------------------------------------------------------------------------

    private String substituteVariables(String json, KickstartData ksdata) {
        KickstartableTree tree = ksdata.getTree();
        String serverFqdn = ConfigDefaults.get().getJavaHostname();

        json = json.replace(VAR_SERVER, serverFqdn);
        json = json.replace(VAR_DISTROTREE, tree.getLabel());
        json = json.replace(VAR_ORG_ID, ksdata.getOrg().getId().toString());

        KickstartUrlHelper helper = new KickstartUrlHelper(tree);
        json = json.replace(VAR_MEDIA_PATH, helper.getKickstartMediaPath());

        // @@CHILD_REPOS@@ — must be substituted before JSON parsing so that the resulting
        // JSON array lands in the right place as a bare value (not a quoted string)
        if (json.contains(VAR_CHILD_REPOS)) {
            json = json.replace(VAR_CHILD_REPOS, buildChildReposJson(ksdata, serverFqdn));
        }

        String activationKey = resolveActivationKey(ksdata);
        if (activationKey != null) {
            json = json.replace(VAR_ACTIVATION_KEY, activationKey);
        }

        return json;
    }

    /**
     * Build a JSON array of child-channel repository objects for Agama's
     * {@code software.extraRepositories}. Equivalent to what the AutoYaST
     * {@code autoyast_channels} snippet produces for {@code <add-on>}.
     *
     * <p>Only channels accessible to the profile's organisation are included.
     */
    private String buildChildReposJson(KickstartData ksdata, String serverFqdn) {
        KickstartableTree tree = ksdata.getTree();
        Channel baseChannel = tree.getChannel();

        List<Channel> childChannels = ChannelFactory.getUserAcessibleChannels(
                ksdata.getOrg().getId(), baseChannel.getId());

        JsonArray repos = new JsonArray();
        for (Channel child : childChannels) {
            String url = "http://" + serverFqdn +
                    "/ks/dist/child/" + child.getLabel() + "/" + tree.getLabel();
            JsonObject repo = new JsonObject();
            repo.addProperty("url", url);
            repo.addProperty("alias", child.getLabel());
            repos.add(repo);
        }
        return GSON.toJson(repos);
    }

    // -------------------------------------------------------------------------
    // Activation key resolution
    // -------------------------------------------------------------------------

    /**
     * Resolve the activation key string for this profile following the same pattern as
     * {@code CobblerProfileCommand.updateCobblerFields()}: the management key comes first,
     * followed by any additional tokens, comma-separated.
     */
    String resolveActivationKey(KickstartData ksdata) {
        KickstartSession ksession =
                KickstartFactory.lookupDefaultKickstartSessionForKickstartData(ksdata);
        if (ksession == null) {
            LOG.warn("No default kickstart session found for Agama profile: {}",
                    ksdata.getLabel());
            return null;
        }
        ActivationKey key = ActivationKeyFactory.lookupByKickstartSession(ksession);
        if (key == null) {
            return null;
        }
        StringBuilder keystring = new StringBuilder(key.getKey());
        if (ksdata.getDefaultRegTokens() != null) {
            for (Token token : ksdata.getDefaultRegTokens()) {
                ActivationKey akey = ActivationKeyFactory.lookupByToken(token);
                keystring.append(",").append(akey.getKey());
            }
        }
        return keystring.toString();
    }

    // -------------------------------------------------------------------------
    // Registration snippet injection
    // -------------------------------------------------------------------------

    /**
     * Inject the registration init-script into {@code scripts.init[]} unless an entry
     * named {@value #REGISTRATION_SCRIPT_ENTRY_NAME} is already present (idempotent).
     *
     * <p>The script body is loaded from the {@value #REGISTRATION_SNIPPET_NAME} file in
     * the Cobbler snippets directory, with @@VARIABLE@@ substitution applied. If the
     * snippet cannot be read, injection is skipped and the profile is returned unchanged.
     */
    private String injectRegistrationIfMissing(String json, KickstartData ksdata) {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        }
        catch (JsonSyntaxException | IllegalStateException e) {
            LOG.warn("Agama profile {} is not a valid JSON object — skipping registration injection",
                    ksdata.getLabel());
            return json;
        }

        // Navigate to scripts.init[], creating the structure if needed
        if (!root.has("scripts")) {
            root.add("scripts", new JsonObject());
        }
        JsonObject scripts = root.getAsJsonObject("scripts");
        if (!scripts.has("init")) {
            scripts.add("init", new JsonArray());
        }
        JsonArray init = scripts.getAsJsonArray("init");

        // Idempotent: skip injection if registration entry is already present
        for (int i = 0; i < init.size(); i++) {
            if (init.get(i).isJsonObject()) {
                JsonObject entry = init.get(i).getAsJsonObject();
                if (entry.has("name") && REGISTRATION_SCRIPT_ENTRY_NAME.equals(
                        entry.get("name").getAsString())) {
                    LOG.debug("Registration script already present in Agama profile {}",
                            ksdata.getLabel());
                    return json;
                }
            }
        }

        String snippetContent = loadAndSubstituteSnippet(ksdata);
        if (snippetContent == null) {
            return json;
        }

        JsonObject regScript = new JsonObject();
        regScript.addProperty("name", REGISTRATION_SCRIPT_ENTRY_NAME);
        regScript.addProperty("content", snippetContent);
        init.add(regScript);

        return GSON.toJson(root);
    }

    /**
     * Read the {@value #REGISTRATION_SNIPPET_NAME} snippet from the Cobbler snippets
     * directory and apply @@VARIABLE@@ substitution to it.
     *
     * @return substituted script body, or {@code null} if the snippet cannot be read
     */
    private String loadAndSubstituteSnippet(KickstartData ksdata) {
        String snippetsDir = ConfigDefaults.get().getCobblerSnippetsDir();
        try {
            String content = FileUtils.readStringFromFile(snippetsDir, REGISTRATION_SNIPPET_NAME);
            if (content == null || content.isEmpty()) {
                LOG.warn("Agama registration snippet is empty: {}/{}",
                        snippetsDir, REGISTRATION_SNIPPET_NAME);
                return null;
            }

            String serverFqdn = ConfigDefaults.get().getJavaHostname();
            content = content.replace(VAR_SERVER, serverFqdn);
            content = content.replace(VAR_DISTROTREE, ksdata.getTree().getLabel());

            String activationKey = resolveActivationKey(ksdata);
            if (activationKey != null) {
                content = content.replace(VAR_ACTIVATION_KEY, activationKey);
            }

            return content;
        }
        catch (Exception e) {
            LOG.warn("Could not read Agama registration snippet {}/{}: {}",
                    snippetsDir, REGISTRATION_SNIPPET_NAME, e.getMessage());
            return null;
        }
    }
}
