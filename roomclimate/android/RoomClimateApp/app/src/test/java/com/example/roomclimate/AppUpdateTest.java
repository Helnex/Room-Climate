package com.example.roomclimate;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.roomclimate.update.AppUpdate;

import org.junit.Test;

/** Обновление предлагается только для версии с бОльшим номером (номер = число коммитов). */
public class AppUpdateTest {
    @Test
    public void newerOnlyWhenCodeIsGreater() {
        assertTrue(AppUpdate.isNewer(58, 57));
        assertFalse(AppUpdate.isNewer(57, 57));
        assertFalse(AppUpdate.isNewer(40, 57));  // откат на старую версию не предлагаем
    }
}
