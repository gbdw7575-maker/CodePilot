#!/bin/sh
set -u
# Only this fixed host-controlled POM and tests are used. Candidate build files are ignored.
mkdir -p /tmp/check
mvn -o -B -ntp -f /verifier/pom.xml -Dmaven.repo.local=/cache -Dcontrol="$1" test
code=$?
printf '%s' "$code" > /tmp/check/maven.exit
# Keep tmpfs mounted until the trusted controller captures bounded reports.
# Controller owns the overall deadline and kills the container on timeout/cancel.
while [ ! -f /tmp/check/release ]; do sleep 0.1; done
exit "$code"
