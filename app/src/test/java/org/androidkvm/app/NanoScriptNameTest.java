package org.androidkvm.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NanoScriptNameTest {
    @Test public void acceptsOnlySimpleShellAndPythonNames() {
        assertTrue(NanoKvmClient.safeScriptName("backup.sh"));
        assertTrue(NanoKvmClient.safeScriptName("wake_PC-2.PY"));
        assertFalse(NanoKvmClient.safeScriptName("../backup.sh"));
        assertFalse(NanoKvmClient.safeScriptName("nested/backup.sh"));
        assertFalse(NanoKvmClient.safeScriptName("x;reboot.sh"));
        assertFalse(NanoKvmClient.safeScriptName("x$(id).sh"));
        assertFalse(NanoKvmClient.safeScriptName("x`id`.sh"));
        assertFalse(NanoKvmClient.safeScriptName("-option.sh"));
        assertFalse(NanoKvmClient.safeScriptName("backup.txt"));
    }
}
