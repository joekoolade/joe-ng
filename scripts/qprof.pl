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
# The sampler behind scripts/qprof.sh. Boots <img> under QEMU with a monitor socket, timestamps every serial line
# into <outdir>/log.txt, and every <intervalSec> writes one line per BUSY core to <outdir>/stacks.txt:
#
#   <sec> cpu<N> | <pc method> | <lr method> | <method> ; <method> ; ...
#
# where the list is every word in the top 2 KB of that core's stack that falls in image code, named through
# <syms.txt> (JOENG_SYMMAP's `start end name` lines). It is a CONSERVATIVE sketch: a stale return address left on
# the stack is named too, so read inclusive counts as an upper bound. A core whose PC is in the scheduler's idle
# loop is not busy and is skipped. Stops at `[main returned`, a CAP EXCEEDED or FAULT line, or <maxSec>.
#
# usage: qprof.pl <img> <syms.txt> <outdir> <intervalSec> <maxSec>
use strict;
use warnings;
no warnings 'portable';
use IO::Socket::UNIX;
use Time::HiRes qw(time sleep);

my ($img, $symf, $out, $iv, $max) = @ARGV;
die "usage: qprof.pl <img> <syms.txt> <outdir> <intervalSec> <maxSec>\n" unless defined $max;
my @s;
open(my $f, "<", $symf) or die "$symf: $!";
while (<$f>)
{
    my ($a, $b, $n) = split;
    push @s, [hex($a), hex($b), $n];
}
die "no symmap lines in $symf (was the image built with JOENG_SYMMAP?)\n" unless @s;
@s = sort { $a->[0] <=> $b->[0] } @s;
my $codeLo = $s[0][0];
my $codeHi = $s[-1][1];

sub name
{
    my $pc = shift;
    my ($lo, $hi) = (0, $#s);
    while ($lo <= $hi)
    {
        my $m = int(($lo + $hi) / 2);
        if ($pc < $s[$m][0])
        {
            $hi = $m - 1;
        }
        elsif ($pc >= $s[$m][1])
        {
            $lo = $m + 1;
        }
        else
        {
            return $s[$m][2];
        }
    }
    return undef;
}

sub label
{
    my $pc = shift;
    return name($pc) // ($pc >= 0x2000000 ? "<arena>" : "<unnamed>");
}

sub idle
{
    my $n = name(shift) // "";
    return $n =~ /VMScheduler\.(pauseMs1|taskYield|pickNext|gcPark|smpSchedulerMain)/;
}

my $sock = "$out/qmon.sock";
unlink $sock;
my $t0 = time;
my $qpid = open(my $q, "-|", "qemu-system-aarch64", "-M", "raspi4b", "-kernel", $img, "-serial", "null",
    "-serial", "stdio", "-display", "none", "-no-reboot", "-monitor", "unix:$sock,server,nowait") or die "qemu: $!";
my $spid = fork();
if ($spid == 0)
{
    close $q;
    sleep 0.2 until -S $sock;
    my $m = IO::Socket::UNIX->new(Peer => $sock) or die "monitor: $!";
    my $cmd = sub
    {
        my $c = shift;
        print $m "$c\n";
        my $buf = "";
        while (1)
        {
            my $r = sysread($m, my $chunk, 65536);
            last unless $r;
            $buf .= $chunk;
            last if $buf =~ /\(qemu\) $/ && length($buf) > length($c) + 10;
        }
        $buf =~ s/\e\[[0-9;]*[A-Za-z]//g;
        return $buf;
    };
    $cmd->("");
    open(my $o, ">", "$out/stacks.txt") or die "$out/stacks.txt: $!";
    $o->autoflush(1);
    while (time - $t0 < $max)
    {
        my $t = sprintf("%.2f", time - $t0);
        for my $c (split /CPU#/, $cmd->("info registers -a"))
        {
            next unless $c =~ /^(\d+)/;
            my $cpu = $1;
            my ($pc) = $c =~ /PC=([0-9a-f]+)/;
            my ($lr) = $c =~ /X30=([0-9a-f]+)/;
            my ($sp) = $c =~ /\bSP=([0-9a-f]+)/;
            next unless defined $pc && defined $lr && defined $sp;
            next if idle(hex($pc));
            my @chain;
            for my $w ($cmd->("xp /256gx 0x$sp") =~ /0x([0-9a-f]{16})/g)
            {
                my $v = hex($w);
                next if $v < $codeLo || $v >= $codeHi;
                my $n = name($v - 4);
                push @chain, $n if defined $n;
            }
            print $o "$t cpu$cpu | " . label(hex($pc)) . " | " . label(hex($lr) - 4) . " | " . join(" ; ", @chain) . "\n";
        }
        sleep $iv;
    }
    exit 0;
}
open(my $log, ">", "$out/log.txt") or die "$out/log.txt: $!";
$log->autoflush(1);
while (my $l = <$q>)
{
    $l =~ s/\r//g;
    printf $log "%8.2f %s", time - $t0, $l;
    last if $l =~ /\[main returned|CAP EXCEEDED|^FAULT / || time - $t0 > $max;
}
kill 'TERM', $qpid;
kill 'TERM', $spid;
waitpid($spid, 0);
