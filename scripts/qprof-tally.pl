#!/usr/bin/perl
#
# Copyright (c) 2026 Joseph Kulig.
#
# This program and the accompanying materials are made available under the
# terms of the Eclipse Public License 2.0 which is available at
# https://www.eclipse.org/legal/epl-2.0/
#
# SPDX-License-Identifier: EPL-2.0
#
# Created: 2026-10-28
#
# Summarise a scripts/qprof.sh run: the busy samples' LEAF (self) methods and the methods found anywhere on their
# stacks (INCLUSIVE; an upper bound, since the stack sketch is conservative). The optional window restricts it to
# [fromSec, toSec] -- read the times off <outdir>/log.txt, where each line of program output is timestamped.
#
# usage: qprof-tally.pl <outdir> [fromSec toSec] [top]
use strict;
use warnings;

my ($dir, $from, $to, $top) = @ARGV;
die "usage: qprof-tally.pl <outdir> [fromSec toSec] [top]\n" unless defined $dir;
$from //= 0;
$to //= 1e9;
$top //= 25;
my (%leaf, %incl);
my $n = 0;
open(my $f, "<", "$dir/stacks.txt") or die "$dir/stacks.txt: $!";
while (<$f>)
{
    chomp;
    my ($head, $pc, $lr, $chain) = split / \| /, $_, 4;
    my ($t) = split / /, $head;
    next if $t < $from || $t > $to;
    $n += 1;
    $leaf{$pc} += 1;
    my %seen = ($pc => 1, $lr => 1);
    $seen{$_} = 1 for split / ; /, ($chain // "");
    $incl{$_} += 1 for keys %seen;
}
die "no busy samples in [$from, $to]\n" unless $n;
printf "busy samples=%d (window %s..%s s)\n--- leaf (self)\n", $n, $from, $to;
my $k = 0;
for my $x (sort { $leaf{$b} <=> $leaf{$a} } keys %leaf)
{
    last if $k++ >= $top;
    printf "%5.1f%%  %s\n", 100 * $leaf{$x} / $n, $x;
}
print "--- inclusive (on stack)\n";
$k = 0;
for my $x (sort { $incl{$b} <=> $incl{$a} } keys %incl)
{
    last if $k++ >= $top;
    printf "%5.1f%%  %s\n", 100 * $incl{$x} / $n, $x;
}
