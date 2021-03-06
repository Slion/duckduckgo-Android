/*
 * Copyright (c) 2019 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.app.cta.ui

import android.net.Uri
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.duckduckgo.app.CoroutineTestRule
import com.duckduckgo.app.InstantSchedulersRule
import com.duckduckgo.app.cta.db.DismissedCtaDao
import com.duckduckgo.app.cta.model.CtaId
import com.duckduckgo.app.cta.model.DismissedCta
import com.duckduckgo.app.global.db.AppDatabase
import com.duckduckgo.app.global.install.AppInstallStore
import com.duckduckgo.app.global.model.Site
import com.duckduckgo.app.onboarding.store.AppStage
import com.duckduckgo.app.onboarding.store.OnboardingStore
import com.duckduckgo.app.onboarding.store.UserStageStore
import com.duckduckgo.app.privacy.model.HttpsStatus
import com.duckduckgo.app.privacy.model.PrivacyGrade
import com.duckduckgo.app.privacy.model.PrivacyPractices
import com.duckduckgo.app.privacy.model.TestEntity
import com.duckduckgo.app.privacy.store.PrivacySettingsStore
import com.duckduckgo.app.runBlocking
import com.duckduckgo.app.settings.db.SettingsDataStore
import com.duckduckgo.app.statistics.VariantManager
import com.duckduckgo.app.statistics.pixels.Pixel
import com.duckduckgo.app.statistics.pixels.Pixel.PixelName.*
import com.duckduckgo.app.survey.db.SurveyDao
import com.duckduckgo.app.survey.model.Survey
import com.duckduckgo.app.survey.model.Survey.Status.SCHEDULED
import com.duckduckgo.app.trackerdetection.model.Entity
import com.duckduckgo.app.trackerdetection.model.TrackingEvent
import com.duckduckgo.app.widget.ui.WidgetCapabilities
import com.nhaarman.mockitokotlin2.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runBlockingTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import java.util.concurrent.TimeUnit

@ExperimentalCoroutinesApi
class CtaViewModelTest {

    @get:Rule
    @Suppress("unused")
    var instantTaskExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    @Suppress("unused")
    val schedulers = InstantSchedulersRule()

    @get:Rule
    @Suppress("unused")
    val coroutineRule = CoroutineTestRule()

    private lateinit var db: AppDatabase

    @Mock
    private lateinit var mockSurveyDao: SurveyDao

    @Mock
    private lateinit var mockWidgetCapabilities: WidgetCapabilities

    @Mock
    private lateinit var mockDismissedCtaDao: DismissedCtaDao

    @Mock
    private lateinit var mockPixel: Pixel

    @Mock
    private lateinit var mockAppInstallStore: AppInstallStore

    @Mock
    private lateinit var mockVariantManager: VariantManager

    @Mock
    private lateinit var mockSettingsDataStore: SettingsDataStore

    @Mock
    private lateinit var mockOnboardingStore: OnboardingStore

    @Mock
    private lateinit var mockPrivacySettingsStore: PrivacySettingsStore

    @Mock
    private lateinit var mockUserStageStore: UserStageStore

    private val requiredDaxOnboardingCtas: List<CtaId> = listOf(
        CtaId.DAX_INTRO,
        CtaId.DAX_DIALOG_SERP,
        CtaId.DAX_DIALOG_TRACKERS_FOUND,
        CtaId.DAX_DIALOG_NETWORK,
        CtaId.DAX_END
    )

    private lateinit var testee: CtaViewModel

    @Before
    fun before() {
        MockitoAnnotations.initMocks(this)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        whenever(mockAppInstallStore.installTimestamp).thenReturn(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1))
        whenever(mockPrivacySettingsStore.privacyOn).thenReturn(true)

        testee = CtaViewModel(
            mockAppInstallStore,
            mockPixel,
            mockSurveyDao,
            mockWidgetCapabilities,
            mockDismissedCtaDao,
            mockVariantManager,
            mockSettingsDataStore,
            mockOnboardingStore,
            mockPrivacySettingsStore,
            mockUserStageStore,
            coroutineRule.testDispatcherProvider
        )
    }

    @After
    fun after() {
        db.close()
    }

    @Test
    fun whenScheduledSurveyChangesAndInstalledDaysMatchThenCtaIsSurvey() {
        val survey = Survey("abc", "http://example.com", 1, SCHEDULED)
        val value = testee.onSurveyChanged(Survey("abc", "http://example.com", 1, SCHEDULED))
        assertEquals(survey, value)
    }

    @Test
    fun whenScheduledSurveyIsNullThenCtaIsNotSurvey() {
        val value = testee.onSurveyChanged(null)
        assertNull(value)
    }

    @Test
    fun whenCtaShownAndCtaIsDaxAndCanNotSendPixelThenPixelIsNotFired() {
        testee.onCtaShown(DaxBubbleCta.DaxIntroCta(mockOnboardingStore, mockAppInstallStore))
        verify(mockPixel, never()).fire(eq(SURVEY_CTA_SHOWN), any(), any())
    }

    @Test
    fun whenCtaShownAndCtaIsDaxAndCanSendPixelThenPixelIsFired() {
        whenever(mockOnboardingStore.onboardingDialogJourney).thenReturn("s:0")
        testee.onCtaShown(DaxBubbleCta.DaxEndCta(mockOnboardingStore, mockAppInstallStore))
        verify(mockPixel, never()).fire(eq(SURVEY_CTA_SHOWN), any(), any())
    }

    @Test
    fun whenCtaShownAndCtaIsNotDaxThenPixelIsFired() {
        testee.onCtaShown(HomePanelCta.Survey(Survey("abc", "http://example.com", 1, SCHEDULED)))
        verify(mockPixel).fire(eq(SURVEY_CTA_SHOWN), any(), any())
    }

    @Test
    fun whenCtaLaunchedPixelIsFired() {
        testee.onUserClickCtaOkButton(HomePanelCta.Survey(Survey("abc", "http://example.com", 1, SCHEDULED)))
        verify(mockPixel).fire(eq(SURVEY_CTA_LAUNCHED), any(), any())
    }

    @Test
    fun whenCtaSecondaryButtonClickedPixelIsFired() {
        val secondaryButtonCta = mock<SecondaryButtonCta>()
        whenever(secondaryButtonCta.secondaryButtonPixel).thenReturn(ONBOARDING_DAX_ALL_CTA_HIDDEN)
        testee.onUserClickCtaSecondaryButton(secondaryButtonCta)
        verify(mockPixel).fire(eq(ONBOARDING_DAX_ALL_CTA_HIDDEN), any(), any())
    }

    @Test
    fun whenCtaDismissedPixelIsFired() = coroutineRule.runBlocking {
        testee.onUserDismissedCta(HomePanelCta.Survey(Survey("abc", "http://example.com", 1, SCHEDULED)))
        verify(mockPixel).fire(eq(SURVEY_CTA_DISMISSED), any(), any())
    }

    @Test
    fun whenSurveyCtaDismissedThenScheduledSurveysAreCancelled() = coroutineRule.runBlocking {
        testee.onUserDismissedCta(HomePanelCta.Survey(Survey("abc", "http://example.com", 1, SCHEDULED)))
        verify(mockSurveyDao).cancelScheduledSurveys()
        verify(mockDismissedCtaDao, never()).insert(any())
    }

    @Test
    fun whenNonSurveyCtaDismissedCtaThenDatabaseNotified() = coroutineRule.runBlocking {
        testee.onUserDismissedCta(HomePanelCta.AddWidgetAuto)
        verify(mockDismissedCtaDao).insert(DismissedCta(CtaId.ADD_WIDGET))
    }

    @Test
    fun whenCtaDismissedAndUserHasPendingOnboardingCtasThenStageNotCompleted() = coroutineRule.runBlocking {
        givenOnboardingActive()
        givenShownDaxOnboardingCtas(emptyList())
        testee.onUserDismissedCta(DaxBubbleCta.DaxEndCta(mockOnboardingStore, mockAppInstallStore))
        verify(mockUserStageStore, times(0)).stageCompleted(any())
    }

    @Test
    fun whenCtaDismissedAndAllDaxOnboardingCtasShownThenStageCompleted() = coroutineRule.runBlocking {
        givenOnboardingActive()
        givenShownDaxOnboardingCtas(requiredDaxOnboardingCtas)
        testee.onUserDismissedCta(DaxDialogCta.DaxSerpCta(mockOnboardingStore, mockAppInstallStore))
        verify(mockUserStageStore).stageCompleted(AppStage.DAX_ONBOARDING)
    }

    @Test
    fun whenHideTipsForeverThenPixelIsFired() = coroutineRule.runBlocking {
        testee.hideTipsForever(HomePanelCta.AddWidgetAuto)
        verify(mockPixel).fire(eq(ONBOARDING_DAX_ALL_CTA_HIDDEN), any(), any())
    }

    @Test
    fun whenHideTipsForeverThenHideTipsSetToTrueOnSettings() = coroutineRule.runBlocking {
        testee.hideTipsForever(HomePanelCta.AddWidgetAuto)
        verify(mockSettingsDataStore).hideTips = true
    }

    @Test
    fun whenHideTipsForeverThenDaxOnboardingStageCompleted() = coroutineRule.runBlocking {
        testee.hideTipsForever(HomePanelCta.AddWidgetAuto)
        verify(mockUserStageStore).stageCompleted(AppStage.DAX_ONBOARDING)
    }

    @Test
    fun whenRegisterDaxBubbleIntroCtaThenDatabaseNotified() = coroutineRule.runBlocking {
        testee.registerDaxBubbleCtaDismissed(DaxBubbleCta.DaxIntroCta(mockOnboardingStore, mockAppInstallStore))
        verify(mockDismissedCtaDao).insert(DismissedCta(CtaId.DAX_INTRO))
    }

    @Test
    fun whenRegisterDaxBubbleEndCtaThenDatabaseNotified() = coroutineRule.runBlocking {
        testee.registerDaxBubbleCtaDismissed(DaxBubbleCta.DaxEndCta(mockOnboardingStore, mockAppInstallStore))
        verify(mockDismissedCtaDao).insert(DismissedCta(CtaId.DAX_END))
    }

    @Test
    fun whenRegisterCtaAndUserHasPendingOnboardingCtasThenStageNotCompleted() = coroutineRule.runBlocking {
        givenOnboardingActive()
        givenShownDaxOnboardingCtas(emptyList())
        testee.registerDaxBubbleCtaDismissed(DaxBubbleCta.DaxEndCta(mockOnboardingStore, mockAppInstallStore))
        verify(mockUserStageStore, times(0)).stageCompleted(any())
    }

    @Test
    fun whenRegisterCtaAndAllDaxOnboardingCtasShownThenStageCompleted() = coroutineRule.runBlocking {
        givenOnboardingActive()
        givenShownDaxOnboardingCtas(requiredDaxOnboardingCtas)
        testee.registerDaxBubbleCtaDismissed(DaxBubbleCta.DaxEndCta(mockOnboardingStore, mockAppInstallStore))
        verify(mockUserStageStore).stageCompleted(AppStage.DAX_ONBOARDING)
    }

    @Test
    fun whenRefreshCtaWhileBrowsingAndSiteIsNullThenReturnNull() = coroutineRule.runBlocking {
        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = true, site = null)
        assertNull(value)
    }

    @Test
    fun whenRefreshCtaWhileBrowsingAndHideTipsIsTrueThenReturnNull() = coroutineRule.runBlocking {
        whenever(mockSettingsDataStore.hideTips).thenReturn(true)
        val site = site(url = "http://www.facebook.com", entity = TestEntity("Facebook", "Facebook", 9.0))

        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = true, site = site)
        assertNull(value)
    }

    @Test
    fun whenRefreshCtaOnHomeTabAndHideTipsIsTrueThenReturnCovidCta() = coroutineRule.runBlocking {
        whenever(mockSettingsDataStore.hideTips).thenReturn(true)
        givenSearchWidgetNoCompatible()

        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = false)
        assertTrue(value is HomeTopPanelCta.CovidCta)
    }

    @Test
    fun whenRefreshCtaOnHomeTabAndHideTipsIsTrueAndWidgetCompatibleThenReturnWidgetCta() = coroutineRule.runBlocking {
        whenever(mockSettingsDataStore.hideTips).thenReturn(true)
        whenever(mockWidgetCapabilities.supportsStandardWidgetAdd).thenReturn(true)
        whenever(mockWidgetCapabilities.supportsAutomaticWidgetAdd).thenReturn(true)

        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = false)
        assertTrue(value is HomePanelCta.AddWidgetAuto)
    }

    @Test
    fun whenRefreshCtaWhileBrowsingAndPrivacyOffThenReturnNull() = coroutineRule.runBlocking {
        givenDaxOnboardingActive()
        whenever(mockPrivacySettingsStore.privacyOn).thenReturn(false)
        val site = site(url = "http://www.facebook.com", entity = TestEntity("Facebook", "Facebook", 9.0))

        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = true, site = site)
        assertNull(value)
    }

    @Test
    fun whenRefreshCtaOnHomeTabAndHideTipsIsTrueThenReturnWidgetAutoCta() = coroutineRule.runBlocking {
        whenever(mockSettingsDataStore.hideTips).thenReturn(true)
        whenever(mockWidgetCapabilities.supportsStandardWidgetAdd).thenReturn(true)
        whenever(mockWidgetCapabilities.supportsAutomaticWidgetAdd).thenReturn(true)
        whenever(mockWidgetCapabilities.hasInstalledWidgets).thenReturn(false)

        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = false)
        assertTrue(value is HomePanelCta.AddWidgetAuto)
    }

    @Test
    fun whenRefreshCtaOnHomeTabAndHideTipsIsTrueThenReturnWidgetInstructionsCta() = coroutineRule.runBlocking {
        whenever(mockSettingsDataStore.hideTips).thenReturn(true)
        whenever(mockWidgetCapabilities.supportsStandardWidgetAdd).thenReturn(true)
        whenever(mockWidgetCapabilities.supportsAutomaticWidgetAdd).thenReturn(false)
        whenever(mockWidgetCapabilities.hasInstalledWidgets).thenReturn(false)

        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = false)
        assertTrue(value is HomePanelCta.AddWidgetInstructions)
    }

    @Test
    fun whenRefreshCtaWhileBrowsingThenReturnNetworkCta() = coroutineRule.runBlocking {
        givenDaxOnboardingActive()
        val site = site(url = "http://www.facebook.com", entity = TestEntity("Facebook", "Facebook", 9.0))
        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = true, site = site)

        assertTrue(value is DaxDialogCta.DaxMainNetworkCta)
    }

    @Test
    fun whenRefreshCtaWhileBrowsingThenReturnTrackersBlockedCta() = coroutineRule.runBlocking {
        givenDaxOnboardingActive()
        val trackingEvent = TrackingEvent("test.com", "test.com", null, TestEntity("test", "test", 9.0), true)
        val site = site(url = "http://www.cnn.com", trackerCount = 1, events = listOf(trackingEvent))
        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = true, site = site)

        assertTrue(value is DaxDialogCta.DaxTrackersBlockedCta)
    }

    @Test
    fun whenRefreshCtaWhileBrowsingAndNoTrackersInformationThenReturnNoSerpCta() = coroutineRule.runBlocking {
        givenDaxOnboardingActive()
        val site = site(url = "http://www.cnn.com", trackerCount = 1)
        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = true, site = site)

        assertTrue(value is DaxDialogCta.DaxNoSerpCta)
    }

    @Test
    fun whenRefreshCtaOnSerpWhileBrowsingThenReturnSerpCta() = coroutineRule.runBlocking {
        givenDaxOnboardingActive()
        val site = site(url = "http://www.duckduckgo.com")
        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = true, site = site)

        assertTrue(value is DaxDialogCta.DaxSerpCta)
    }


    @Test
    fun whenRefreshCtaWhileBrowsingThenReturnNoSerpCta() = coroutineRule.runBlocking {
        givenDaxOnboardingActive()
        val site = site(url = "http://www.wikipedia.com")
        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = true, site = site)

        assertTrue(value is DaxDialogCta.DaxNoSerpCta)
    }

    @Test
    fun whenRefreshCtaOnHomeTabThenValueReturnedIsNotDaxDialogCtaType() = coroutineRule.runBlocking {
        givenDaxOnboardingActive()
        val site = site(url = "http://www.wikipedia.com")
        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = false, site = site)

        assertTrue(value !is DaxDialogCta)
    }

    @Test
    fun whenRefreshCtaOnHomeTabAndIntroCtaWasNotPreviouslyShownThenIntroCtaShown() = coroutineRule.runBlocking {
        givenDaxOnboardingActive()
        whenever(mockDismissedCtaDao.exists(CtaId.DAX_INTRO)).thenReturn(false)
        whenever(mockDismissedCtaDao.exists(CtaId.DAX_DIALOG_SERP)).thenReturn(true)

        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = false)
        assertTrue(value is DaxBubbleCta.DaxIntroCta)
    }

    @Test
    fun whenRefreshCtaOnHomeTabAndIntroCtaWasShownThenEndCtaShown() = coroutineRule.runBlocking {
        givenDaxOnboardingActive()
        whenever(mockDismissedCtaDao.exists(CtaId.DAX_INTRO)).thenReturn(true)
        givenAtLeastOneDaxDialogCtaShown()

        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = false)
        assertTrue(value is DaxBubbleCta.DaxEndCta)
    }

    @Test
    fun whenRefreshCtaOnHomeTabAndDaxEndCtaWasPreviouslyShownThenCovidCtaShown() = runBlockingTest {
        givenShownDaxOnboardingCtas(listOf(CtaId.DAX_INTRO, CtaId.DAX_END))

        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = false)
        assertTrue(value is HomeTopPanelCta.CovidCta)
    }

    @Test
    fun whenRefreshCtaWhileBrowsingWithDaxOnboardingCompletedButNotAllCtasWereShownThenReturnNull() = runBlockingTest {
        givenShownDaxOnboardingCtas(listOf(CtaId.DAX_INTRO))
        givenDaxOnboardingCompleted()

        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = true)
        assertNull(value)
    }

    @Test
    fun whenRefreshCtaOnHomeTabWithDaxOnboardingCompletedButNotAllCtasWereShownThenReturnCovidCta() = runBlockingTest {
        givenShownDaxOnboardingCtas(listOf(CtaId.DAX_INTRO))
        givenDaxOnboardingCompleted()
        givenSearchWidgetNoCompatible()

        val value = testee.refreshCta(coroutineRule.testDispatcher, isBrowserShowing = false)
        assertTrue(value is HomeTopPanelCta.CovidCta)
    }

    private suspend fun givenDaxOnboardingActive() {
        whenever(mockUserStageStore.getUserAppStage()).thenReturn(AppStage.DAX_ONBOARDING)
    }

    private suspend fun givenDaxOnboardingCompleted() {
        whenever(mockUserStageStore.getUserAppStage()).thenReturn(AppStage.ESTABLISHED)
    }

    private fun givenSearchWidgetNoCompatible() {
        whenever(mockWidgetCapabilities.supportsStandardWidgetAdd).thenReturn(false)
        whenever(mockWidgetCapabilities.supportsAutomaticWidgetAdd).thenReturn(false)
        whenever(mockWidgetCapabilities.hasInstalledWidgets).thenReturn(false)
    }

    private fun givenAtLeastOneDaxDialogCtaShown() {
        whenever(mockDismissedCtaDao.exists(CtaId.DAX_DIALOG_TRACKERS_FOUND)).thenReturn(true)
    }

    private fun givenShownDaxOnboardingCtas(shownCtas: List<CtaId>) {
        shownCtas.forEach {
            whenever(mockDismissedCtaDao.exists(it)).thenReturn(true)
        }
    }

    private suspend fun givenOnboardingActive() {
        whenever(mockUserStageStore.getUserAppStage()).thenReturn(AppStage.DAX_ONBOARDING)
    }

    private fun setCovidCtaShown() {
        whenever(mockDismissedCtaDao.exists(CtaId.COVID)).thenReturn(true)
    }

    private fun site(
        url: String = "http://www.test.com",
        uri: Uri? = Uri.parse(url),
        https: HttpsStatus = HttpsStatus.SECURE,
        trackerCount: Int = 0,
        events: List<TrackingEvent> = emptyList(),
        majorNetworkCount: Int = 0,
        allTrackersBlocked: Boolean = true,
        privacyPractices: PrivacyPractices.Practices = PrivacyPractices.UNKNOWN,
        entity: Entity? = null,
        grade: PrivacyGrade = PrivacyGrade.UNKNOWN,
        improvedGrade: PrivacyGrade = PrivacyGrade.UNKNOWN
    ): Site {
        val site: Site = mock()
        whenever(site.url).thenReturn(url)
        whenever(site.uri).thenReturn(uri)
        whenever(site.https).thenReturn(https)
        whenever(site.entity).thenReturn(entity)
        whenever(site.trackingEvents).thenReturn(events)
        whenever(site.trackerCount).thenReturn(trackerCount)
        whenever(site.majorNetworkCount).thenReturn(majorNetworkCount)
        whenever(site.allTrackersBlocked).thenReturn(allTrackersBlocked)
        whenever(site.privacyPractices).thenReturn(privacyPractices)
        whenever(site.calculateGrades()).thenReturn(Site.SiteGrades(grade, improvedGrade))
        return site
    }
}
