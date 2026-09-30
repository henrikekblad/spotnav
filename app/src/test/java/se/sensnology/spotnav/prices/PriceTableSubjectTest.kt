package se.sensnology.spotnav.prices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.sensnology.spotnav.chart.ChartMarket
import se.sensnology.spotnav.ha.authority.AuthorityAvailability
import se.sensnology.spotnav.ha.authority.HaPresentation
import se.sensnology.spotnav.ha.authority.PriceRequestKey
import se.sensnology.spotnav.ha.authority.VisibleAuthority
import se.sensnology.spotnav.ha.settings.HaPlanningSettings
import se.sensnology.spotnav.planning.LocalPlanningInputs
import se.sensnology.spotnav.testing.RelayFixtures
import se.sensnology.spotnav.testing.SettingsFixtures
import se.sensnology.spotnav.widget.WidgetSettings

/** The Price Table's launch subject, as the screen builds and re-checks it. */
class PriceTableSubjectTest {
    private val profileId = "local-a"
    private val catalogue = listOf(RelayFixtures.se4, RelayFixtures.no1)
    private val presentation = HaPresentation.QUARTER_HOUR

    /** The widget's own record: NO1 and its own fiscal flags, disagreeing with the charger. */
    private val local = WidgetSettings(
        area = "NO1", vat = true, tax = true, taxMinorUnit = 8.0, transfer = false, gridFeeMinorUnit = 0.0
    )

    /** The charger's confirmed record: SE4, a VAT rate, an explicit zero tax, a decimal fee. */
    private val record = SettingsFixtures.parsed(
        revision = 5,
        areaId = "SE4",
        overrides = org.json.JSONArray().put(
            SettingsFixtures.override(
                areaId = "SE4",
                vat = SettingsFixtures.fiscal(enabled = true, value = 12.0),
                tax = SettingsFixtures.fiscal(enabled = true, value = 0.0),
                transfer = SettingsFixtures.fiscal(enabled = true, value = 5.5)
            )
        )
    )

    private fun external(record: HaPlanningSettings, revision: Int = record.revision): VisibleAuthority.AutoRemote =
        VisibleAuthority.AutoRemote(
            settings = record,
            // Home Assistant reports the plan for a paired charger, in both modes: no calculation
            // inputs travel in this state (see AuthorityPlan).
            remotePlan = null,
            revision = revision,
            availability = AuthorityAvailability.CONFIRMED
        )

    private fun subjectFor(authority: VisibleAuthority?) = PriceTableSubjects.of(
        authority = authority,
        localArea = local.area,
        localInputs = LocalPlanningInputs.ofOrNull(local),
        intervalMinutes = local.intervalMinutes,
        profileId = profileId,
        generation = 1,
        catalogue = catalogue
    )

    @Test
    fun theTableOpensInHomeAssistantsAreaDespiteAConflictingLocalOne() {
        val subject = subjectFor(external(record))

        assertEquals("SE4", subject.areaId)
        assertEquals(PriceRequestKey(profileId, "SE4", 1), subject.requestKey)
        assertEquals(5, subject.revision)
        assertEquals("SE4", subject.market?.areaId)
        assertEquals(12.0, subject.market?.vat?.effectiveValue)
        assertEquals(0.0, subject.market?.tax?.effectiveValue)
        assertEquals(5.5, subject.market?.transfer?.effectiveValue)
        // And no calculation inputs at all, for either mode: the phone does not price rows from a
        // plan it may not install, so the figures are named unavailable rather than invented (see
        // inputsOf).
        assertNull(
            "Home Assistant owns the plan for a paired charger, so the table has no inputs",
            subject.inputs
        )
        assertTrue("the widget's own flags are not what the record states", local.vat)
    }

    @Test
    fun anUnpairedTableStillPricesTheWidgetsOwnArea() {
        val subject = subjectFor(VisibleAuthority.LocalOwner(null))

        assertEquals("NO1", subject.areaId)
        assertNull("the widget's own record has no settings revision", subject.revision)
        assertEquals(subject.inputs, LocalPlanningInputs.ofOrNull(local))
        assertEquals(subject.inputs?.let(ChartMarket::of), subject.market)
    }

    @Test
    fun changingTheAuthorityAreaInvalidatesTheFormerSubjectImmediately() {
        val before = subjectFor(external(record))
        // The authority now names another market -- the same profile, the same screen pass.
        val moved = external(record.copy(revision = 7, areaId = "NO1"))

        assertFalse(
            "a response for the previous market is inert",
            PriceTableSubjects.stillCurrent(before, moved, local.area, catalogue)
        )
        assertFalse("and so is one for another profile", before.accepts(PriceRequestKey("local-b", "SE4", 1)))
        assertFalse("or another screen pass", before.accepts(PriceRequestKey(profileId, "SE4", 2)))
        assertTrue(
            "while the subject's own identity still renders",
            PriceTableSubjects.stillCurrent(before, external(record), local.area, catalogue)
        )

        // The new subject is the new market's, at the new revision: no prices, no selection and no
        // memo from the former one can be reused for it.
        val after = subjectFor(moved)
        assertEquals("NO1", after.areaId)
        assertEquals(7, after.revision)
        assertEquals(PriceRequestKey(profileId, "NO1", 1), after.requestKey)
        assertFalse(before.requestKey == after.requestKey)

        // An offline screen keeps the last confirmed market: the record is still the subject.
        assertEquals("SE4", subjectFor(VisibleAuthority.ReadOnlyOffline(record)).areaId)
    }

    @Test
    fun aLateAnswerForAnAreaTheAuthorityLeftIsNotRendered() {
        val no1 = subjectFor(VisibleAuthority.LocalOwner(null))
        val se4 = subjectFor(external(record))

        // The table was launched while the widget's own area was the authority, and the answer
        // arrives after the record took over.
        assertFalse(PriceTableSubjects.stillCurrent(no1, external(record), local.area, catalogue))
        assertTrue(PriceTableSubjects.stillCurrent(se4, external(record), local.area, catalogue))
        assertFalse("and the two are different subjects", no1.requestKey == se4.requestKey)
    }

    @Test
    fun aPairedSubjectBearsItsAreaAndRevisionAndNeverInventedInputs() {
        // Both paired modes answer the same way: the area and the revision are the record's, and
        // there are no calculation inputs -- Home Assistant owns the plan, so the table names its
        // per-row figures unavailable rather than pricing them from a proposal this phone invented.
        val auto = VisibleAuthority.AutoRemote(
            settings = record,
            remotePlan = null,
            revision = record.revision,
            availability = AuthorityAvailability.CONFIRMED
        )
        for (paired in listOf(auto, external(record))) {
            val subject = subjectFor(paired)
            assertEquals("SE4", subject.areaId)
            assertEquals(5, subject.revision)
            assertEquals("SE4", subject.market?.areaId)
            assertNull("no calculation inputs for $paired", subject.inputs)
        }
    }

    @Test
    fun noHandoverIsEverAcceptedForAPairedSubject() {
        val subject = subjectFor(external(record))
        val inputs = LocalPlanningInputs.ofOrNull(local)!!

        // A memo marks rows only for the exact complete subject, and a paired subject has no
        // calculation inputs at all -- so nothing is ever handed to it: the table names its figures
        // unavailable instead of reusing a plan this phone computed for a charger it does not own
        // (see PriceTableSubject.owns).
        assertFalse("the subject's own (absent) inputs cannot own it", subject.owns(null, 5))
        assertFalse("nor the widget's own inputs", subject.owns(inputs, 5))
        assertFalse("nor another revision", subject.owns(inputs, 6))
    }
}
