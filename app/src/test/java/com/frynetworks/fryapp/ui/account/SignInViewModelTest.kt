package com.frynetworks.fryapp.ui.account

import com.frynetworks.fryapp.auth.NewUserProfile
import com.frynetworks.fryapp.auth.NextAuthApi
import com.frynetworks.fryapp.auth.SessionProfile
import com.frynetworks.fryapp.auth.SessionRepository
import com.frynetworks.fryapp.auth.SessionState
import com.frynetworks.fryapp.auth.SessionStore
import com.frynetworks.fryapp.auth.SignInResult
import com.frynetworks.fryapp.auth.SignInUseCase
import com.frynetworks.fryapp.auth.SignOutUseCase
import com.frynetworks.fryapp.network.dashboard.cookies.CookieStore
import com.frynetworks.fryapp.network.dashboard.cookies.PersistentCookieJar
import com.frynetworks.fryapp.wallet.BridgeEvent
import com.frynetworks.fryapp.wallet.WalletBridge
import com.frynetworks.fryapp.wallet.WalletVendor
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelTest {

    private val address = "HXWYLLZDPTM5OXS3DPARMTG52RSBMMCQNKT4L2LZRRXYPNAWJBT6VIW6WU"
    private val bridgeEvents = MutableSharedFlow<BridgeEvent>(extraBufferCapacity = 4)

    private class MemoryStore : SessionStore {
        var p: SessionProfile? = null
        override fun load() = p
        override fun save(profile: SessionProfile?) { p = profile }
    }

    private class MemoryCookies : CookieStore {
        var s: List<String> = emptyList()
        override fun load() = s
        override fun save(serialized: List<String>) { s = serialized }
    }

    @Before fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun session(): SessionRepository =
        SessionRepository(mockk<NextAuthApi>(relaxed = true), PersistentCookieJar(MemoryCookies()), MemoryStore())

    private fun vm(session: SessionRepository, signIn: SignInUseCase, installed: Set<WalletVendor> = setOf(WalletVendor.PERA)): SignInViewModel {
        val bridge = mockk<WalletBridge> { every { events } returns bridgeEvents }
        val signOut = mockk<SignOutUseCase>(relaxed = true)
        return SignInViewModel(session, signIn, signOut, bridge, installedVendors = { installed })
    }

    @Test
    fun `initial state shows which wallets are installed and no busy work`() = runTest {
        val vm = vm(session(), mockk())
        val s = vm.uiState.value
        assertTrue(s.installed.contains(WalletVendor.PERA))
        assertFalse(s.installed.contains(WalletVendor.DEFLY))
        assertFalse(s.busy)
        assertNull(s.error)
        assertTrue(s.session is SessionState.SignedOut)
    }

    @Test
    fun `a successful connect ends busy and reflects the signed-in session`() = runTest {
        val session = session()
        val useCase = mockk<SignInUseCase> {
            coEvery { signIn(WalletVendor.PERA, null) } coAnswers {
                session.markSignedIn(SessionProfile(address, vendor = WalletVendor.PERA), fingerprintBound = true)
                SignInResult.Success(SessionProfile(address))
            }
        }
        val vm = vm(session, useCase)
        vm.connect(WalletVendor.PERA)
        val s = vm.uiState.value
        assertFalse(s.busy)
        assertNull(s.error)
        assertEquals(address, (s.session as SessionState.SignedIn).profile.address)
    }

    @Test
    fun `a new wallet switches the screen into profile-entry mode and resubmits with the profile`() = runTest {
        val session = session()
        var received: NewUserProfile? = null
        val useCase = mockk<SignInUseCase> {
            coEvery { signIn(WalletVendor.PERA, isNull()) } returns SignInResult.NeedsProfile(address)
            coEvery { signIn(WalletVendor.PERA, ofType<NewUserProfile>()) } coAnswers {
                received = secondArg()
                SignInResult.Success(SessionProfile(address))
            }
        }
        val vm = vm(session, useCase)
        vm.connect(WalletVendor.PERA)
        assertEquals(address, vm.uiState.value.needsProfileFor)
        vm.submitProfile("new@fry.test", "New", "User")
        assertEquals(NewUserProfile("new@fry.test", "New", "User"), received)
        assertNull(vm.uiState.value.needsProfileFor)
    }

    @Test
    fun `a failure surfaces its code and message and clears busy`() = runTest {
        val useCase = mockk<SignInUseCase> {
            coEvery { signIn(WalletVendor.DEFLY, null) } returns SignInResult.Failure("USER_REJECTED", "rejected in wallet")
        }
        val vm = vm(session(), useCase, installed = setOf(WalletVendor.DEFLY))
        vm.connect(WalletVendor.DEFLY)
        assertEquals("USER_REJECTED", vm.uiState.value.error?.code)
        assertFalse(vm.uiState.value.busy)
    }

    @Test
    fun `the last wallet deep link is remembered so the user can reopen the wallet`() = runTest {
        val vm = vm(session(), mockk())
        bridgeEvents.emit(BridgeEvent.OpenUri("perawallet-wc://", WalletVendor.PERA))
        assertEquals("perawallet-wc://", vm.uiState.value.lastWalletUri)
    }
}
