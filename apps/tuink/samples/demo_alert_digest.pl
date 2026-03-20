#!/usr/bin/env perl
# Demo: Parses alert lines and builds a digest report by level and service.
use strict;
use warnings;
use feature 'say';

sub parse_line {
    my ($line) = @_;
    return undef if !defined $line || $line =~ /^\s*$/;

    chomp $line;
    my ($ts, $level, $service, $message) = split /\|/, $line, 4;
    return undef if !defined $message;

    $ts =~ s/^\s+|\s+$//g;
    $level =~ s/^\s+|\s+$//g;
    $service =~ s/^\s+|\s+$//g;
    $message =~ s/^\s+|\s+$//g;

    return {
        ts      => $ts,
        level   => uc($level),
        service => lc($service),
        message => $message,
    };
}

sub summarize {
    my ($events) = @_;
    my %by_level;
    my %by_service;

    for my $e (@$events) {
        $by_level{$e->{level}}++;
        $by_service{$e->{service}}++;
    }

    return {
        total      => scalar(@$events),
        by_level   => \%by_level,
        by_service => \%by_service,
    };
}

sub top_keys {
    my ($hash_ref, $limit) = @_;
    my @keys = sort {
        $hash_ref->{$b} <=> $hash_ref->{$a}
            || $a cmp $b
    } keys %$hash_ref;

    if (@keys > $limit) {
        @keys = @keys[0 .. $limit - 1];
    }
    return \@keys;
}

sub print_report {
    my ($summary) = @_;

    say "digest report";
    say "=============";
    say "total events: $summary->{total}";
    say "";

    say "by level:";
    for my $lvl (@{ top_keys($summary->{by_level}, 10) }) {
        printf "  %-8s %4d\n", $lvl, $summary->{by_level}{$lvl};
    }
    say "";

    say "top services:";
    for my $svc (@{ top_keys($summary->{by_service}, 5) }) {
        printf "  %-12s %4d\n", $svc, $summary->{by_service}{$svc};
    }
}

sub demo_lines {
    return (
        '2026-03-02T08:11:00Z|info|api|request completed',
        '2026-03-02T08:11:02Z|warn|billing|card check timeout',
        '2026-03-02T08:11:05Z|error|billing|gateway unavailable',
        '2026-03-02T08:11:08Z|info|api|request completed',
        '2026-03-02T08:11:11Z|info|worker|job done id=177',
        '2026-03-02T08:11:12Z|error|worker|panic in stage normalize',
        '2026-03-02T08:11:14Z|warn|api|slow query > 900ms',
        '2026-03-02T08:11:17Z|info|search|cache hit key=u:17',
        '2026-03-02T08:11:20Z|info|search|cache hit key=u:98',
        '2026-03-02T08:11:21Z|error|api|invalid state transition',
        '2026-03-02T08:11:23Z|warn|worker|retry exhausted task=31',
        '2026-03-02T08:11:28Z|info|billing|invoice generated #7512',
    );
}

my @events;
for my $line (demo_lines()) {
    my $event = parse_line($line);
    push @events, $event if defined $event;
}

my $summary = summarize(\@events);
print_report($summary);

exit 0;
