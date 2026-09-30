package se.sensnology.spotnav.widget

import se.sensnology.spotnav.chargers.ChargerProfile
import se.sensnology.spotnav.chargers.ChargerProfileStore
import se.sensnology.spotnav.ha.client.HomeAssistantSettings

/**
 * Resolves the [ChargerProfile] a widget controls from its own stored binding, never from the app-wide
 * active profile. `null` when unbound or when the bound profile was deleted; the binding is never
 * redirected or mutated here.
 */
internal object WidgetChargerResolver {
    fun resolve(settings: WidgetSettings, store: ChargerProfileStore): ChargerProfile? =
        settings.chargerProfileId?.let { store.getProfile(it) }
}
