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
    private void keyEvent(int action, int keyCode, int repeat, int metaState, int deviceId, String expected) throws Exception {
        KeyEvent event = new KeyEvent(0, 0, action, keyCode, repeat, metaState, deviceId, 0);
        assertTrue(action == KeyEvent.ACTION_UP ? mView.onKeyUp(keyCode, event) : mView.onKeyDown(keyCode, event));
        assertEquals(expected, drainInput());
    }

    private void repeat(int keyCode, int metaState, String expected) throws Exception {
        keyEvent(KeyEvent.ACTION_DOWN, keyCode, 1, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, expected);
    }

    private void release(int keyCode, int metaState, String expected) throws Exception {
        keyEvent(KeyEvent.ACTION_UP, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, expected);
    }

    @Test
    public void negotiatedRepeatsAndReleasesReachSession() throws Exception {
        enter("\033[>3u");
        press(KeyEvent.KEYCODE_DPAD_UP, 0, "\033[A");
        repeat(KeyEvent.KEYCODE_DPAD_UP, 0, "\033[1;1:2A");
        release(KeyEvent.KEYCODE_DPAD_UP, 0, "\033[1;1:3A");
        press(KeyEvent.KEYCODE_I, KeyEvent.META_CTRL_ON, "\033[105;5u");
        repeat(KeyEvent.KEYCODE_I, KeyEvent.META_CTRL_ON, "\033[105;5:2u");
        release(KeyEvent.KEYCODE_I, KeyEvent.META_CTRL_ON, "\033[105;5:3u");
        press(KeyEvent.KEYCODE_F3, KeyEvent.META_SHIFT_ON, "\033[13;2~");
        release(KeyEvent.KEYCODE_F3, KeyEvent.META_SHIFT_ON, "\033[13;2:3~");
        press(KeyEvent.KEYCODE_ESCAPE, 0, "\033[27u");
        release(KeyEvent.KEYCODE_ESCAPE, 0, "\033[27;1:3u");
    }

    @Test
    public void eventReportingCanBeEnabledWithoutDisambiguation() throws Exception {
        enter("\033[>2u");
        press(KeyEvent.KEYCODE_I, KeyEvent.META_CTRL_ON, "\t");
        repeat(KeyEvent.KEYCODE_I, KeyEvent.META_CTRL_ON, "\033[105;5:2u");
        release(KeyEvent.KEYCODE_I, KeyEvent.META_CTRL_ON, "\033[105;5:3u");
        press(KeyEvent.KEYCODE_DPAD_UP, 0, "\033[A");
        release(KeyEvent.KEYCODE_DPAD_UP, 0, "\033[1;1:3A");
        press(KeyEvent.KEYCODE_ENTER, KeyEvent.META_CTRL_ON, "\r");
        release(KeyEvent.KEYCODE_ENTER, KeyEvent.META_CTRL_ON, "");
    }

    @Test
    public void textAndRecoveryKeysKeepPressAndRepeatPaths() throws Exception {
        enter("\033[>3u");
        press(KeyEvent.KEYCODE_A, 0, "a");
        repeat(KeyEvent.KEYCODE_A, 0, "a");
        release(KeyEvent.KEYCODE_A, 0, "");
        press(KeyEvent.KEYCODE_A, KeyEvent.META_SHIFT_ON, "A");
        release(KeyEvent.KEYCODE_A, KeyEvent.META_SHIFT_ON, "");
        int[] keys = {KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_DEL};
        String[] bytes = {"\r", "\t", "\u007f"};
        for (int i = 0; i < keys.length; i++) {
            press(keys[i], 0, bytes[i]);
            repeat(keys[i], 0, bytes[i]);
            release(keys[i], 0, "");
        }
        press(KeyEvent.KEYCODE_ENTER, KeyEvent.META_CTRL_ON, "\033[13;5u");
        repeat(KeyEvent.KEYCODE_ENTER, KeyEvent.META_CTRL_ON, "\033[13;5:2u");
        release(KeyEvent.KEYCODE_ENTER, KeyEvent.META_CTRL_ON, "");
    }

    @Test
    public void releasesUseCurrentModifiersAndRememberLayoutIdentity() throws Exception {
        enter("\033[>3u");
        press(KeyEvent.KEYCODE_EQUALS, KeyEvent.META_CTRL_ON | KeyEvent.META_SHIFT_ON, "\033[61;6u");
        release(KeyEvent.KEYCODE_EQUALS, 0, "\033[61;1:3u");
        press(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.META_CTRL_ON, "\033[1;5A");
        release(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.META_SHIFT_ON, "\033[1;2:3A");
    }

    @Test
    public void orphanedCanceledAndModeChangedReleasesAreSuppressed() throws Exception {
        enter("\033[>3u");
        release(KeyEvent.KEYCODE_DPAD_UP, 0, "");
        press(KeyEvent.KEYCODE_DPAD_UP, 0, "\033[A");
        enter("\033[>0u\033[<u");
        release(KeyEvent.KEYCODE_DPAD_UP, 0, "");
        press(KeyEvent.KEYCODE_DPAD_UP, 0, "\033[A");
        enter("\033c\033[>3u");
        release(KeyEvent.KEYCODE_DPAD_UP, 0, "");
        press(KeyEvent.KEYCODE_DPAD_UP, 0, "\033[A");
        KeyEvent canceled = new KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_CANCELED);
        assertTrue(mView.onKeyUp(KeyEvent.KEYCODE_DPAD_UP, canceled));
        assertEquals("", drainInput());
        release(KeyEvent.KEYCODE_DPAD_UP, 0, "");
    }

    @Test
    public void releasesDoNotCrossScreensOrDevices() throws Exception {
        enter("\033[>3u");
        press(KeyEvent.KEYCODE_DPAD_UP, 0, "\033[A");
        enter("\033[?1049h\033[>3u");
        release(KeyEvent.KEYCODE_DPAD_UP, 0, "");
        enter("\033[?1049l");
        press(KeyEvent.KEYCODE_DPAD_UP, 0, "\033[A");
        keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP, 0, 0, 42, "");
        release(KeyEvent.KEYCODE_DPAD_UP, 0, "\033[1;1:3A");
    }

    @Test
    public void shortcutsScrollAndSoftInputDoNotSynthesizeReleases() throws Exception {
        enter("\033[>3u");
        press(KeyEvent.KEYCODE_PAGE_UP, KeyEvent.META_SHIFT_ON, "");
        release(KeyEvent.KEYCODE_PAGE_UP, 0, "");
        mView.inputCodePoint(TerminalView.KEY_EVENT_SOURCE_SOFT_KEYBOARD, 'i', true, false);
        assertEquals("\033[105;5u", drainInput());
        release(KeyEvent.KEYCODE_I, KeyEvent.META_CTRL_ON, "");
        mView.setTerminalViewClient(new TermuxTerminalViewClientBase() {
            @Override
            public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
                return true;
            }
        });
        press(KeyEvent.KEYCODE_I, KeyEvent.META_CTRL_ON, "");
        release(KeyEvent.KEYCODE_I, KeyEvent.META_CTRL_ON, "");
        mView.setTerminalViewClient(new TermuxTerminalViewClientBase() {
            @Override
            public boolean onKeyDown(int keyCode, KeyEvent event, TerminalSession session) {
                return true;
            }
        });
        press(KeyEvent.KEYCODE_DPAD_UP, 0, "");
        release(KeyEvent.KEYCODE_DPAD_UP, 0, "");
    }

    @Test
    public void disabledEventReportingKeepsRepeatAsPress() throws Exception {
        for (int flags : new int[]{0, 1}) {
            enter("\033[=" + flags + "u");
            press(KeyEvent.KEYCODE_DPAD_UP, 0, "\033[A");
            repeat(KeyEvent.KEYCODE_DPAD_UP, 0, "\033[A");
            release(KeyEvent.KEYCODE_DPAD_UP, 0, "");
        }
    }

    @Test
    public void keypadReleaseKeepsIdentityWhenNumLockChanges() throws Exception {
        enter("\033[>3u");
        press(KeyEvent.KEYCODE_NUMPAD_0, KeyEvent.META_NUM_LOCK_ON, "\033[57399;129u");
        release(KeyEvent.KEYCODE_NUMPAD_0, 0, "\033[57399;1:3u");
        press(KeyEvent.KEYCODE_NUMPAD_0, 0, "\033[57425u");
        release(KeyEvent.KEYCODE_NUMPAD_0, KeyEvent.META_NUM_LOCK_ON, "\033[57425;129:3u");
        enter("\033[=2u");
        press(KeyEvent.KEYCODE_NUMPAD_0, 0, "\033[2~");
        release(KeyEvent.KEYCODE_NUMPAD_0, KeyEvent.META_NUM_LOCK_ON, "\033[2;129:3~");
    }

    @Test
    public void escapeEventsDoNotRequireDisambiguation() throws Exception {
        enter("\033[>2u");
        press(KeyEvent.KEYCODE_ESCAPE, 0, "\033");
        repeat(KeyEvent.KEYCODE_ESCAPE, 0, "\033[27;1:2u");
        release(KeyEvent.KEYCODE_ESCAPE, 0, "\033[27;1:3u");
    }

    @Test
    public void focusLossAndScreenRoundTripDiscardPendingReleases() throws Exception {
        enter("\033[>3u");
        press(KeyEvent.KEYCODE_DPAD_UP, 0, "\033[A");
        enter("\033[?1049h\033[?1049l");
        release(KeyEvent.KEYCODE_DPAD_UP, 0, "");
        press(KeyEvent.KEYCODE_DPAD_UP, 0, "\033[A");
        ReflectionHelpers.callInstanceMethod(mView, "onFocusChanged",
            ReflectionHelpers.ClassParameter.from(boolean.class, false),
            ReflectionHelpers.ClassParameter.from(int.class, 0),
            ReflectionHelpers.ClassParameter.from(android.graphics.Rect.class, null));
        release(KeyEvent.KEYCODE_DPAD_UP, 0, "");
    }

    @Test
    @Config(shadows = AltGrCharacterMap.class)
    public void altGrTextDoesNotProduceReleaseEvents() throws Exception {
        enter("\033[>3u");
        int altGr = KeyEvent.META_ALT_ON | KeyEvent.META_ALT_RIGHT_ON;
        press(KeyEvent.KEYCODE_SPACE, altGr, "\u00a0");
        repeat(KeyEvent.KEYCODE_SPACE, altGr, "\u00a0");
        release(KeyEvent.KEYCODE_SPACE, altGr, "");
        press(KeyEvent.KEYCODE_E, altGr | KeyEvent.META_CTRL_ON, "\033[8364;5u");
        release(KeyEvent.KEYCODE_E, altGr, "\033[8364;1:3u");
    }

    @Test
    public void applicationKeyUpCanConsumeRelease() throws Exception {
        enter("\033[>3u");
        press(KeyEvent.KEYCODE_DPAD_UP, 0, "\033[A");
        mView.setTerminalViewClient(new TermuxTerminalViewClientBase() {
            @Override
            public boolean onKeyUp(int keyCode, KeyEvent event) {
                return true;
            }
        });
        release(KeyEvent.KEYCODE_DPAD_UP, 0, "");
    }

    @Test
    public void extraKeyBridgeSendsOnePressWithoutPendingRelease() throws Exception {
        enter("\033[>3u");
        new com.termux.shared.termux.terminal.io.TerminalExtraKeys(mView) {
            void tap() {
                onTerminalExtraKeyButtonClick(null, "UP", true, false, false, false);
            }
        }.tap();
        assertEquals("\033[1;5A", drainInput());
        keyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP, 0, KeyEvent.META_CTRL_ON, 0, "");
    }

    @Test
    public void virtualFnPressEncodingUsesActualNegotiatedFlags() throws Exception {
        TermuxTerminalViewClient client = new TermuxTerminalViewClient(null, null);
        client.mVirtualFnKeyDown = true;
        for (int flags : new int[]{0, 1, 2, 3}) {
            enter("\033[=" + flags + "u");
            assertTrue(client.onCodePoint('b', false, mSession));
            assertEquals((flags & 1) != 0 ? "\033[98;3u" : "\033b", drainInput());
            assertTrue(client.onCodePoint('e', false, mSession));
            assertEquals((flags & 1) != 0 ? "\033[27u" : "\033", drainInput());
            // The separately deferred virtual Fn Ctrl-loss behavior remains unchanged.
            assertTrue(client.onCodePoint('w', true, mSession));
            assertEquals("\033[A", drainInput());
        }
    }

    @Test
    public void keypadRepeatsPreserveInitialReleaseIdentity() throws Exception {
        enter("\033[>3u");
        press(KeyEvent.KEYCODE_NUMPAD_0, KeyEvent.META_NUM_LOCK_ON, "\033[57399;129u");
        repeat(KeyEvent.KEYCODE_NUMPAD_0, 0, "\033[57425;1:2u");
        release(KeyEvent.KEYCODE_NUMPAD_0, 0, "\033[57399;1:3u");
        press(KeyEvent.KEYCODE_NUMPAD_0, 0, "\033[57425u");
        repeat(KeyEvent.KEYCODE_NUMPAD_0, KeyEvent.META_NUM_LOCK_ON, "\033[57399;129:2u");
        release(KeyEvent.KEYCODE_NUMPAD_0, KeyEvent.META_NUM_LOCK_ON, "\033[57425;129:3u");
        enter("\033[=2u");
        press(KeyEvent.KEYCODE_NUMPAD_0, 0, "\033[2~");
        repeat(KeyEvent.KEYCODE_NUMPAD_0, KeyEvent.META_NUM_LOCK_ON, "0");
        release(KeyEvent.KEYCODE_NUMPAD_0, KeyEvent.META_NUM_LOCK_ON, "\033[2;129:3~");
    }

    @Test
    public void repeatsDoNotCreateOrRevivePendingReleases() throws Exception {
        enter("\033[>3u");
        repeat(KeyEvent.KEYCODE_DPAD_UP, 0, "\033[1;1:2A");
        release(KeyEvent.KEYCODE_DPAD_UP, 0, "");
        press(KeyEvent.KEYCODE_DPAD_UP, 0, "\033[A");
        enter("\033[>0u\033[<u");
        repeat(KeyEvent.KEYCODE_DPAD_UP, 0, "\033[1;1:2A");
        release(KeyEvent.KEYCODE_DPAD_UP, 0, "");
    }

    @Test
    public void flag2ModifiedRecoveryRepeatsReportEventType() throws Exception {
        enter("\033[>2u");
        int[] keys = {KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_DEL};
        int[] modifiers = {KeyEvent.META_CTRL_ON, KeyEvent.META_SHIFT_ON, KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON};
        String[] pressBytes = {"\r", "\033[Z", "\033\u007f"};
        String[] repeatBytes = {"\033[13;5:2u", "\033[9;2:2u", "\033[127;3:2u"};
        for (int i = 0; i < keys.length; i++) {
            press(keys[i], modifiers[i], pressBytes[i]);
            repeat(keys[i], modifiers[i], repeatBytes[i]);
            release(keys[i], modifiers[i], "");
        }
        String[] plainBytes = {"\r", "\t", "\u007f"};
        for (int i = 0; i < keys.length; i++) {
            press(keys[i], KeyEvent.META_NUM_LOCK_ON | KeyEvent.META_CAPS_LOCK_ON, plainBytes[i]);
            repeat(keys[i], KeyEvent.META_NUM_LOCK_ON | KeyEvent.META_CAPS_LOCK_ON, plainBytes[i]);
            release(keys[i], 0, "");
        }
    }

}
