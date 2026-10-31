#!/bin/sh
set -eu
umask 077
# The writable volume owns temporary files too. No small tmpfs cap truncates a
# complete conversation, SQLite snapshot or encrypted transfer.
mkdir -p "$TMPDIR"
exec /usr/local/bin/modelatlas-vps "$@"
