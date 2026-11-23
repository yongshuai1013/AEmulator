#!/bin/sh
# Host test of vmount.h: helpers and syscall numbers are cut out of run64.c itself, so the test follows the source.
set -e
cd "$(dirname "$0")"
SRC=../${1:-37}/run64.c
T=$(mktemp -d)
sed -n '/^enum {$/,/^};$/p' $SRC | sed -n '1,/^};$/p' > $T/enums.inc
{ for f in slen seq pre atou; do grep -m1 "^static [a-z0-9 ]*\*\?$f(" $SRC; done; grep -m1 "^static char \*utoa(" $SRC
  sed -n '/^static int norm(/,/^}/p' $SRC
  sed -n '/^static long fake_memfile(/,/^}/p' $SRC; } > $T/helpers.inc
cp vmount_test.c $T/
cc -O1 -g -fsanitize=address -Wall -Wno-unused-function -Wno-unused-variable -I$T -I. -o $T/t $T/vmount_test.c
$T/t
rm -rf $T
