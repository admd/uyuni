#!/usr/bin/env python3
import socket, urllib.error, time, sys, logging
socket.setdefaulttimeout(30)
import obs_maven.repo as repo
import obs_maven.core as core
_orig_get_binary = repo.Repo.get_binary
def _get_binary_with_retry(self, path, target, mtime):
    for attempt in range(1, 5):
        try:
            _orig_get_binary(self, path, target, mtime)
            return
        except urllib.error.URLError as e:
            if attempt < 4:
                wait = 30 * attempt
                logging.warning("obs-to-maven: URLError on attempt %d/4: %s. Retrying in %ds...", attempt, e, wait)
                time.sleep(wait)
            else:
                raise
repo.Repo.get_binary = _get_binary_with_retry
sys.exit(core.main())
