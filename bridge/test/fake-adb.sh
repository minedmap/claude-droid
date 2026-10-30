#!/usr/bin/env bash
[ "$1" = devices ] || exit 1
printf "List of devices attached\nlocalhost:40123\tdevice\nemulator-5554\toffline\n\n"
