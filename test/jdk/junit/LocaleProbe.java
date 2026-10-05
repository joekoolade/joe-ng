/*
 * Copyright (c) 2026 Joseph Kulig.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Created: 2026-10-03
 */
import java.util.HashMap;
import java.util.IllformedLocaleException;
import java.util.Locale;
import java.util.Map;

/**
 * STOCK {@code java.util.Locale} on the metal -- the overlay is deleted.
 *
 * <p>The host oracle is a stock JVM running this file with {@code -Duser.language=en -Duser.country=
 * -Duser.variant= -Duser.script=}, which is the default joe-ng's seeded properties produce
 * ({@code StaticProperty} falls back to {@code en} with no country). Every arm prints its answer; the gate is a
 * byte-for-byte diff of the two runs.
 *
 * <p>The arms that matter most are the ones the old overlay could not answer or answered by approximation:
 * constants built from {@code BaseLocale} and their IDENTITY ({@code Locale.of("en") == Locale.ENGLISH} is the
 * interning cache), language-tag parsing and canonicalisation ({@code iw} -> {@code he}), the Builder and its
 * refusals, a locale with extensions, and the locale-sensitive {@code String} case mapping -- Turkish dotless
 * i, which is a wrong STRING rather than an error when the locale is not honoured.
 */
public class LocaleProbe
{
    private static int failures;

    private static void say(String label, Object got)
    {
        System.out.println(label + " = " + got);
    }

    private static void check(String label, String got, String want)
    {
        if (got.equals(want))
        {
            System.out.println("ok   " + label + " = " + got);
        }
        else
        {
            failures++;
            System.out.println("FAIL " + label + " = " + got + " (want " + want + ")");
        }
    }

    private static String codes(String s)
    {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length())
        {
            if (i > 0)
            {
                sb.append(',');
            }
            sb.append((int) s.charAt(i));
            i += 1;
        }
        return sb.toString();
    }

    public static void main(String[] args)
    {
        // Constants and their identity: Locale.of answers the SAME interned object.
        say("ENGLISH", Locale.ENGLISH);
        say("US", Locale.US);
        say("UK tag", Locale.UK.toLanguageTag());
        say("ROOT tag", Locale.ROOT.toLanguageTag());
        say("CHINA == SIMPLIFIED_CHINESE", Locale.CHINA == Locale.SIMPLIFIED_CHINESE);
        say("of(en) == ENGLISH", Locale.of("en") == Locale.ENGLISH);
        say("of(en,US) == US", Locale.of("en", "US") == Locale.US);
        say("new Locale(en) equals ENGLISH", new Locale("en").equals(Locale.ENGLISH));
        say("US.hashCode == of(en,US).hashCode", Locale.US.hashCode() == Locale.of("en", "US").hashCode());
        say("ENGLISH equals US", Locale.ENGLISH.equals(Locale.US));

        // Components and case normalisation.
        Locale l = Locale.of("FR", "ca");
        say("of(FR,ca)", l + " lang=" + l.getLanguage() + " country=" + l.getCountry());
        Locale v = Locale.of("de", "DE", "POSIX");
        say("variant", v + " variant=" + v.getVariant());

        // The default -- what the seeded properties produce.
        say("default", Locale.getDefault());
        say("default FORMAT", Locale.getDefault(Locale.Category.FORMAT));
        say("default DISPLAY", Locale.getDefault(Locale.Category.DISPLAY));

        // Language tags: parsing, canonicalisation, scripts, extensions, garbage.
        say("forLanguageTag(fr-CA)", Locale.forLanguageTag("fr-CA"));
        say("forLanguageTag(zh-Hant-TW) script", Locale.forLanguageTag("zh-Hant-TW").getScript());
        say("forLanguageTag(iw) lang", Locale.forLanguageTag("iw").getLanguage());
        say("of(iw) lang", Locale.of("iw").getLanguage());
        Locale ext = Locale.forLanguageTag("th-TH-u-nu-thai");
        say("ext tag", ext.toLanguageTag());
        say("ext unicode nu", ext.getUnicodeLocaleType("nu"));
        say("ext u value", ext.getExtension('u'));
        say("ext co tag", Locale.forLanguageTag("de-u-co-phonebk").toLanguageTag());
        say("ext x tag", Locale.forLanguageTag("en-x-foo").toLanguageTag());
        say("ext stripExtensions", ext.stripExtensions().toLanguageTag());
        say("forLanguageTag(garbage!) tag", Locale.forLanguageTag("garbage!").toLanguageTag());
        say("toLanguageTag(de_DE_POSIX)", v.toLanguageTag());

        // The Builder, and its refusal of an ill-formed subtag (an exception, not a silent accept).
        Locale b = new Locale.Builder().setLanguage("de").setRegion("CH").setScript("Latn").build();
        say("builder", b + " tag=" + b.toLanguageTag());
        String bad;
        try
        {
            new Locale.Builder().setLanguage("not a language");
            bad = "no throw";
        }
        catch (IllformedLocaleException e)
        {
            bad = "IllformedLocaleException";
        }
        say("builder bad language", bad);

        // Locale-sensitive case mapping: Turkish dotted/dotless i, and the ROOT control.
        Locale tr = Locale.of("tr");
        check("TITLE lower(tr)", codes("TITLE".toLowerCase(tr)), "116,305,116,108,101");
        check("TITLE lower(ROOT)", codes("TITLE".toLowerCase(Locale.ROOT)), "116,105,116,108,101");
        check("title upper(tr)", codes("title".toUpperCase(tr)), "84,304,84,76,69");
        check("title upper(ROOT)", codes("title".toUpperCase(Locale.ROOT)), "84,73,84,76,69");

        // StringJoiner: its toString() goes through JavaLangAccess.join, which answered null on this VM.
        java.util.StringJoiner sj = new java.util.StringJoiner(", ", "[", "]");
        sj.add("a");
        sj.add("b");
        say("StringJoiner", sj.toString());
        say("StringJoiner empty", new java.util.StringJoiner("-", "<", ">").toString());

        // As a map key: equals/hashCode must agree across the two ways of naming one locale.
        Map<Locale, String> m = new HashMap<>();
        m.put(Locale.US, "us");
        say("map get(of(en,US))", m.get(Locale.of("en", "US")));

        System.out.println("LocaleProbe done, failures=" + failures);
    }
}
