# Count the demo suite's self-checking lines that print a value DIFFERENT from the one they want.
#
# Most suite demos print `label = <got> (want <expected>)`. A wrong value there is a silent wrong answer: no
# marker fires, the run "passes", and the line sits in the log until someone reads it. DigestDemo's
# `sha256 clone = ...FORK-WRONG` stayed that way across several suite runs on QEMU and the Pi.
#
# A line is checked when it ENDS with `(want ...)`. It matches when, whitespace collapsed:
#   - the text before ` (want ` equals the wanted value, or ends with it right after a space or `=`
#     (`long a[i] += 5     = 12 (want 12)`, `mie message=Input length = 3 (want Input length = 3)`), or
#   - the wanted value is a comma list and equals the values printed after each `=`
#     (`Math.random = 1 differs = 1 (want 1, 1)`).
# Text after ` -- ` inside the parenthesis is a note for the reader and is dropped before comparing.
# Lines with text after the parenthesis (`finish HML (want HML)  steps ...`) are not checked here; those
# demos have their own summary lines.
#
# Usage: awk -f scripts/want-mismatch.awk <log>   -> prints each mismatch, then `checked=N mismatch=M`.

function norm(s)
{
    gsub(/[ \t]+/, " ", s)
    sub(/^ /, "", s)
    sub(/ $/, "", s)
    return s
}

function endsWithAtBoundary(got, want,    gl, wl, c)
{
    gl = length(got)
    wl = length(want)
    if (wl == 0 || gl < wl || substr(got, gl - wl + 1) != want)
    {
        return 0
    }
    if (gl == wl)
    {
        return 1
    }
    c = substr(got, gl - wl, 1)
    return c == " " || c == "="
}

function listMatches(got, want,    n, wv, rest, k, gv, i)
{
    if (index(want, ", ") == 0)
    {
        return 0
    }
    n = split(want, wv, ", ")
    rest = got
    k = 0
    while (match(rest, /= *[^ =]+/))
    {
        k += 1
        gv[k] = substr(rest, RSTART, RLENGTH)
        sub(/^= */, "", gv[k])
        rest = substr(rest, RSTART + RLENGTH)
    }
    if (k != n)
    {
        return 0
    }
    for (i = 1; i <= n; i++)
    {
        if (gv[i] != wv[i])
        {
            return 0
        }
    }
    return 1
}

{
    line = $0
    sub(/\r$/, "", line)
    if (line !~ / \(want .*\)[ \t]*$/)
    {
        next
    }
    sub(/[ \t]+$/, "", line)
    at = 0
    rest = line
    while ((p = index(rest, " (want ")) > 0)
    {
        at += p
        rest = substr(rest, p + 1)
    }
    got = norm(substr(line, 1, at - 1))
    want = substr(line, at + 7, length(line) - at - 7)
    sub(/ -- .*$/, "", want)                    # `(want 3 -- 0 means the ctor was skipped)`: a note, not a value
    want = norm(want)
    checked += 1
    if (got == want || endsWithAtBoundary(got, want) || listMatches(got, want))
    {
        next
    }
    mismatch += 1
    print "  WANT MISMATCH: " substr(line, 1, 200)
}

END {                                   # awk needs the brace on END's line
    printf "checked=%d mismatch=%d\n", checked, mismatch
}
