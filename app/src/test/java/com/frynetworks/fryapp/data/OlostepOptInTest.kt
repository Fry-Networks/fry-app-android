package com.frynetworks.fryapp.data

import android.content.Context
import android.content.SharedPreferences
import app.cash.turbine.test
import com.frynetworks.fryapp.auth.SessionRepository
import com.frynetworks.fryapp.auth.SessionState
import com.frynetworks.fryapp.ble.FryGattContract
import com.frynetworks.fryapp.ui.settings.SettingsViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Olostep opt-in: off by default, persisted by [SettingsRepository], surfaced by [SettingsViewModel]. */
class OlostepOptInTest {

    /** In-memory SharedPreferences; apply() and commit() write straight through. */
    private class FakePrefs : SharedPreferences {
        val map = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String, defValue: String?) = map[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String, defValue: Int) = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long) = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float) = map[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = map[key] as? Boolean ?: defValue
        override fun contains(key: String) = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { pending.remove(key) }
            override fun clear() = apply { map.clear() }
            override fun commit(): Boolean { map.putAll(pending); return true }
            override fun apply() { map.putAll(pending) }
        }
    }

    private fun repo(prefs: FakePrefs): SettingsRepository {
        val context = mockk<Context>()
        every { context.getSharedPreferences("fry_settings", Context.MODE_PRIVATE) } returns prefs
        return SettingsRepository(context)
    }

    private fun viewModel(repo: SettingsRepository): SettingsViewModel {
        val session = mockk<SessionRepository>()
        every { session.state } returns MutableStateFlow<SessionState>(SessionState.SignedOut)
        return SettingsViewModel(repo, session, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
    }

    @Test
    fun `opt-in is off by default and no choice is recorded`() {
        val r = repo(FakePrefs())
        assertFalse(r.getOlostepOptIn())
        assertFalse(r.hasOlostepChoice())
    }

    @Test
    fun `turning it on persists across a new repository over the same prefs`() {
        val prefs = FakePrefs()
        repo(prefs).setOlostepOptIn(true)
        val again = repo(prefs)
        assertTrue(again.getOlostepOptIn())
        assertTrue(again.hasOlostepChoice())
    }

    @Test
    fun `turning it back off persists false and keeps the choice recorded so the board is told`() {
        val prefs = FakePrefs()
        val r = repo(prefs)
        r.setOlostepOptIn(true)
        r.setOlostepOptIn(false)
        val again = repo(prefs)
        assertFalse(again.getOlostepOptIn())
        assertTrue(again.hasOlostepChoice())
    }

    @Test
    fun `the view model flow defaults to false and emits true after the setter`() = runTest {
        val prefs = FakePrefs()
        val vm = viewModel(repo(prefs))
        vm.olostepOptIn.test {
            assertFalse(awaitItem())
            vm.setOlostepOptIn(true)
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(repo(prefs).getOlostepOptIn())
    }

    @Test
    fun `the view model flow is seeded from the stored value`() = runTest {
        val prefs = FakePrefs()
        repo(prefs).setOlostepOptIn(true)
        assertTrue(viewModel(repo(prefs)).olostepOptIn.value)
    }

    @Test
    fun `the olostep opt-in characteristic keeps the sibling base and is the next free id`() {
        val u = FryGattContract.CHAR_OLOSTEP_OPT_IN
        assertEquals("4652590b-0001-4000-8000-4652594e4554", u.toString())
        assertEquals(FryGattContract.CHAR_DEVICE_STATUS.toString().replaceFirst("4652590a", "4652590b"), u.toString())
    }
}
