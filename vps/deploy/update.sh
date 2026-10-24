#!/bin/sh
set -eu
exec sh "$(dirname "$0")/manage.sh" update "$@"
