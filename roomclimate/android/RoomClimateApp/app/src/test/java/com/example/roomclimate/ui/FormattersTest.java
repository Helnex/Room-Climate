package com.example.roomclimate.ui;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Locale;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FormattersTest {
    private Locale savedLocale;

    @Before
    public void setUp() {
        savedLocale = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("ru-RU"));
    }

    @After
    public void tearDown() {
        Locale.setDefault(savedLocale);
    }

    @Test
    public void celsiusUnitShownWithDegreeSign() {
        assertEquals("°C", Formatters.unit("C"));
        assertEquals("°C", Formatters.unit("°C"));
        assertEquals("%", Formatters.unit("%"));
        assertEquals("", Formatters.unit(null));
    }

    @Test
    public void numberUsesRussianDecimalComma() {
        assertEquals("24,1", Formatters.number(24.1));
    }

    @Test
    public void serverUtcTimeShownInLocalZone() {
        String shown = Formatters.time("2026-11-12T05:02:43+00:00", TimeZone.getTimeZone("Asia/Yekaterinburg"));
        assertEquals("12.11.2026 10:02:43", shown);
    }

    @Test
    public void unparsableTimeReturnedAsIs() {
        assertEquals("вчера", Formatters.time("вчера"));
        assertEquals("", Formatters.time(null));
    }

    @Test
    public void initialsFromCyrillicNames() {
        assertEquals("ИП", Formatters.initials("иван", "петров"));
        assertEquals("Ё", Formatters.initials("ёжик", ""));
        assertEquals("", Formatters.initials(null, "  "));
    }

    @Test
    public void emailValidationAcceptsCyrillicDomains() {
        assertTrue(AuthValidation.isEmail("ivan@mail.ru"));
        assertTrue(AuthValidation.isEmail("иван@почта.рф"));
        assertFalse(AuthValidation.isEmail("@@@"));
        assertFalse(AuthValidation.isEmail("ivan@mail"));
        assertFalse(AuthValidation.isEmail("iv an@mail.ru"));
    }
}
