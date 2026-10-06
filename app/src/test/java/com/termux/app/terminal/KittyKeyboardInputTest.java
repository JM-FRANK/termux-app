package com.termux.app.terminal;

import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowKeyCharacterMap;
import org.robolectric.util.ReflectionHelpers;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Exercise Android key events through TerminalView into the real session input queue, without a PTY. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class KittyKeyboardInputTest {
    private TerminalView mView;
    private TerminalSession mSession;

    @Before
    public void setUp() {
        mSession = new TerminalSession("/bin/sh", "/", new String[0], new String[0], 100,
            new TermuxTerminalSessionClientBase());
        TerminalEmulator emulator = new TerminalEmulator(mSession, 20, 4, 8, 16, 100, null);
        ReflectionHelpers.setField(mSession, "mEmulator", emulator);
        ReflectionHelpers.setField(mSession, "mShellPid", 1);
        mView = new TerminalView(RuntimeEnvironment.getApplication(), null);
        mView.setTerminalViewClient(new TermuxTerminalViewClientBase());
        ReflectionHelpers.setField(mView, "mTermSession", mSession);
        mView.mEmulator = emulator;
    }

    private void enter(String sequence) {
        byte[] bytes = sequence.getBytes(StandardCharsets.UTF_8);
        mView.mEmulator.append(bytes, bytes.length);
    }

    private String drainInput() throws Exception {
        Object queue = ReflectionHelpers.getField(mSession, "mTerminalToProcessIOQueue");
        Method read = queue.getClass().getDeclaredMethod("read", byte[].class, boolean.class);
        read.setAccessible(true);
        byte[] bytes = new byte[4096];
        int count = (Integer) read.invoke(queue, bytes, false);
        return new String(bytes, 0, count, StandardCharsets.UTF_8);
    }

    private void press(int keyCode, int metaState, String expected) throws Exception {
        KeyEvent event = new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0);
        assertTrue(mView.onKeyDown(keyCode, event));
        assertEquals(expected, drainInput());
    }

    @Test
    public void hardwareShortcutsRetainKeyIdentity() throws Exception {
        enter("\033[>1u");
        press(KeyEvent.KEYCODE_0, KeyEvent.META_CTRL_ON, "\033[48;5u");
        press(KeyEvent.KEYCODE_I, KeyEvent.META_CTRL_ON, "\033[105;5u");
        press(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON | KeyEvent.META_SHIFT_ON, "\033[97;6u");
        press(KeyEvent.KEYCODE_EQUALS, KeyEvent.META_CTRL_ON | KeyEvent.META_SHIFT_ON, "\033[61;6u");
    }

    @Test
    public void modifiedEnterAndTabStayDistinct() throws Exception {
        enter("\033[>1u");
        press(KeyEvent.KEYCODE_ENTER, KeyEvent.META_SHIFT_ON, "\033[13;2u");
        press(KeyEvent.KEYCODE_ENTER, KeyEvent.META_CTRL_ON, "\033[13;5u");
        press(KeyEvent.KEYCODE_TAB, KeyEvent.META_CTRL_ON, "\033[9;5u");
        press(KeyEvent.KEYCODE_TAB, 0, "\t");
        press(KeyEvent.KEYCODE_ENTER, 0, "\r");
        press(KeyEvent.KEYCODE_ESCAPE, 0, "\033[27u");
    }

    @Test
    public void plainTextAndRestoredModeKeepLegacyBehavior() throws Exception {
        enter("\033[>1u");
        press(KeyEvent.KEYCODE_A, KeyEvent.META_SHIFT_ON, "A");
        enter("\033[<u");
        press(KeyEvent.KEYCODE_I, KeyEvent.META_CTRL_ON, "\t");
        press(KeyEvent.KEYCODE_ENTER, KeyEvent.META_SHIFT_ON, "\r");
        press(KeyEvent.KEYCODE_0, KeyEvent.META_CTRL_ON, "0");
    }

    @Test
    public void softInputAndApplicationShortcuts() throws Exception {
        enter("\033[>1u");
        mView.inputCodePoint(TerminalView.KEY_EVENT_SOURCE_SOFT_KEYBOARD, '0', true, false);
        assertEquals("\033[48;5u", drainInput());
        mView.inputCodePoint(TerminalView.KEY_EVENT_SOURCE_SOFT_KEYBOARD, 0x4E2D, false, false);
        assertEquals("中", drainInput());
        mView.setTerminalViewClient(new TermuxTerminalViewClientBase() {
            @Override
            public boolean onKeyDown(int keyCode, KeyEvent event, TerminalSession session) {
                return true;
            }
        });
        press(KeyEvent.KEYCODE_0, KeyEvent.META_CTRL_ON, "");
    }

    /** A layout fixture where right Alt composes NBSP on Space and Euro on E. */
    @Implements(KeyCharacterMap.class)
    public static class AltGrCharacterMap extends ShadowKeyCharacterMap {
        @Override
        @Implementation
        protected int get(int keyCode, int metaState) {
            if ((metaState & KeyEvent.META_ALT_RIGHT_ON) != 0) {
                if (keyCode == KeyEvent.KEYCODE_SPACE) return 0x00A0;
                if (keyCode == KeyEvent.KEYCODE_E) return 0x20AC;
            }
            return super.get(keyCode, metaState);
        }
    }

    @Test
    @Config(shadows = AltGrCharacterMap.class)
    public void altGrTextUsesTheActiveLayout() throws Exception {
        int altGr = KeyEvent.META_ALT_ON | KeyEvent.META_ALT_RIGHT_ON;
        press(KeyEvent.KEYCODE_SPACE, altGr, "\u00a0");
        enter("\033[>1u");
        press(KeyEvent.KEYCODE_SPACE, altGr, "\u00a0");
        press(KeyEvent.KEYCODE_E, altGr, "\u20ac");
        press(KeyEvent.KEYCODE_SPACE, altGr | KeyEvent.META_CTRL_ON, "\033[160;5u");
        enter("\033[<u");
        press(KeyEvent.KEYCODE_SPACE, altGr, "\u00a0");
    }

    @Test
    public void ctrlAndLeftAltSpaceRemainDisambiguated() throws Exception {
        enter("\033[>1u");
        press(KeyEvent.KEYCODE_SPACE, 0, " ");
        press(KeyEvent.KEYCODE_SPACE, KeyEvent.META_CTRL_ON, "\033[32;5u");
        press(KeyEvent.KEYCODE_SPACE, KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON, "\033[32;3u");
        enter("\033[<u");
        press(KeyEvent.KEYCODE_SPACE, KeyEvent.META_CTRL_ON, "\0");
    }

    @Test
    public void shiftPageKeysKeepScrollbackPrecedence() throws Exception {
        enter("1\r\n2\r\n3\r\n4\r\n5\r\n6\r\n7\r\n8\r\n9\r\n10\r\n");
        for (boolean kitty : new boolean[]{false, true}) {
            if (kitty) enter("\033[>1u");
            press(KeyEvent.KEYCODE_PAGE_UP, KeyEvent.META_SHIFT_ON, "");
            assertEquals(-mView.mEmulator.mRows, (int) ReflectionHelpers.getField(mView, "mTopRow"));
            press(KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.META_SHIFT_ON, "");
            assertEquals(0, (int) ReflectionHelpers.getField(mView, "mTopRow"));
            if (kitty) enter("\033[<u");
        }
    }

    @Test
    public void otherPageKeysStillReachTheApplication() throws Exception {
        enter("\033[>1u");
        press(KeyEvent.KEYCODE_PAGE_UP, KeyEvent.META_CTRL_ON, "\033[5;5~");
        press(KeyEvent.KEYCODE_PAGE_DOWN, 0, "\033[6~");
    }
}
