#!/bin/bash
set -xe

if [[ "$(uname)" == "Darwin" ]]; then
  PODMAN_CMD="podman"
else
  PODMAN_CMD="sudo -i podman"
fi

src_dir=$(cd $(dirname "$0")/../.. && pwd -P)

$PODMAN_CMD exec server bash -c "cp /testsuite/podman_runner/debug_logging.properties /etc/tomcat/logging.properties"

# Create missing directories that will be created by the new RPM https://github.com/uyuni-project/uyuni/pull/7651
$PODMAN_CMD exec server bash -c "[ -d /usr/share/susemanager/www ] || mkdir -p /usr/share/susemanager/www"
$PODMAN_CMD exec server bash -c "[ -d /usr/share/susemanager/www/htdocs ] || mkdir -p /usr/share/susemanager/www/htdocs"
$PODMAN_CMD exec server bash -c "[ -d /usr/share/susemanager/www/tomcat/webapps ] || mkdir -p /usr/share/susemanager/www/tomcat/webapps"

# WORKAROUND: If ivy build fails, try again, because 50% of times fails when
# downloading the new jar files from download.opensuse.org.
# 
# build.opensuse.org publishes our packages into download.opensuse.org. However,
# this is not a "static" directory. If packages get rebuild, old packages are
# removed, new packages are published and new metadata is created. Then, the
# whole thing is updated in the download.opensuse.org mirrors all around the
# world.
#
# Packages from devel repos can get rebuild any time, i.e. when their
# dependencies are updates (i.e. java).
# 
# Thus, the repos from devel projects that are published into
# download.opensuse.org, are not stable, their metadata can get rebuilt at any
# time, packages are removed, etc. and plus, this takes time to be propagated,
# so mirrors are very often outdated, have outdated metadata or outdated
# packages, or both.
#
# So, we can not consider download.opensuse.org reliable for devel projects.
# While we do not have a mirror for the jar files, the easiest workaround is
# to try again and hope it succeeds.

# Inject a wrapper around obs-to-maven to fix two bugs in repo.Repo.get_binary:
# 1. urlopen() has no timeout, so a TCP stall hangs for ~110s (OS retransmission limit).
#    Fix: socket.setdefaulttimeout(30) caps each attempt at 30s.
# 2. urlopen() wraps TimeoutError and redirect failures as urllib.error.URLError, but the
#    retry loop only catches ConnectionResetError/ConnectionRefusedError/HTTPError, so
#    URLError escapes immediately with no retry.
#    Fix: outer retry catches URLError with increasing backoff.
$PODMAN_CMD cp ${src_dir}/testsuite/podman_runner/obs-to-maven-wrapper.py server:/usr/local/bin/obs-to-maven
$PODMAN_CMD exec server chmod +x /usr/local/bin/obs-to-maven

set +e # Temporarily disable 'exit on error'
# Use a heredoc to avoid complex quoting and shell parsing issues for multiline scripts
$PODMAN_CMD exec -i server bash << 'EOF'
  MAX_WAIT=1200
  RETRY_DELAY=60
  START_TIME=$(date +%s)
  cd /java
  while ! ant -f manager-build.xml ivy; do
    CURRENT_TIME=$(date +%s)
    ELAPSED_SECONDS=$(( CURRENT_TIME - START_TIME ))
    if [ "${ELAPSED_SECONDS}" -ge "${MAX_WAIT}" ]; then
      echo "Ant Ivy build failed: Timed out after $(( MAX_WAIT / 60 )) minutes." >&2
      echo "Check GH Runner IP:" >&2
      curl https://api.ipify.org ||:
      exit 1
    fi
    echo "Ant Ivy build failed (elapsed: ${ELAPSED_SECONDS}s / ${MAX_WAIT}s). Retrying in ${RETRY_DELAY}s (mirrors may be temporarily unavailable)..."
    sleep ${RETRY_DELAY}
  done
  echo "Ant Ivy build succeeded."
EOF
ANT_BUILD_IVY_COMMAND=$?
set -e # Re-enable 'exit on error'

if [ $ANT_BUILD_IVY_COMMAND -ne 0 ]; then
    echo "ERROR: The main command failed. Exiting script with original status $ANT_BUILD_IVY_COMMAND." >&2
    exit $ANT_BUILD_IVY_COMMAND
fi
###

$PODMAN_CMD exec server bash -c "rctomcat stop"
$PODMAN_CMD exec server bash -c "cd /java && ant -f manager-build.xml -Ddeploy.mode=local refresh-branding-jar deploy"
$PODMAN_CMD exec server bash -c "cd /java && ant -f manager-build.xml apidoc-jsp"
$PODMAN_CMD exec server bash -c "mkdir /usr/share/susemanager/www/tomcat/webapps/rhn/apidoc/ && rsync -av /java/build/reports/apidocs/jsp/ /usr/share/susemanager/www/tomcat/webapps/rhn/apidoc/"
$PODMAN_CMD exec server bash -c "set -xe;npm --prefix web ci --ignore-scripts --save=false --omit=dev;npm --prefix web run build -- --check-spec=false; rsync -a web/html/src/dist/ /usr/share/susemanager/www/htdocs/"
$PODMAN_CMD exec server bash -c "rctomcat restart"
$PODMAN_CMD exec server bash -c "rctaskomatic restart"

# mgr-push
$PODMAN_CMD exec server bash -c "cp /client/tools/mgr-push/*.py /usr/lib/python3.6/site-packages/rhnpush/"
$PODMAN_CMD exec server bash -c "cp /client/tools/mgr-push/rhnpushrc /etc/sysconfig/rhn/rhnpushrc"

$PODMAN_CMD exec server bash -c "cd /susemanager-utils/susemanager-sls/; cp -R modules/* /usr/share/susemanager/modules; cp -R salt/* /usr/share/susemanager/salt; cp -R src/modules/* /usr/share/susemanager/salt/_modules; cp -R src/grains/* /usr/share/susemanager/salt/_grains; cp -R src/states/* /usr/share/susemanager/salt/_states; cp -R src/beacons/* /usr/share/susemanager/salt/_beacons; cp -R salt-ssh/* /usr/share/susemanager/salt-ssh"
$PODMAN_CMD exec server bash -c "cd /susemanager/; cp src/mgr-salt-ssh /usr/bin/; chmod a+x /usr/bin/mgr-salt-ssh"
$PODMAN_CMD exec server bash -c "cd /susemanager/src; cp mgr_sync/*.py /usr/lib/python3.6/site-packages/spacewalk/susemanager/mgr_sync/; cp *.py /usr/lib/python3.6/site-packages/spacewalk/susemanager/; mv /usr/lib/python3.6/site-packages/spacewalk/susemanager/mgr_bootstrap_data.py /usr/share/susemanager/"
