package dev.irshadm.volta.data.ismart

import dev.irshadm.volta.core.ismart.Vehicle
import kotlin.test.Test
import kotlin.test.assertEquals

class CarIdentityTest {
    private fun identity(model: String?, series: String?) = CarIdentity.of(Vehicle("VIN", series, model, null, false))

    @Test fun `known models match the name however MG words it`() {
        assertEquals(CarModel.Windsor, CarIdentity("Windsor Pro").model)
        assertEquals(CarModel.Comet, CarIdentity("COMET EV EXCLUSIVE FC").model)
        assertEquals(CarModel.ZsEv, CarIdentity("ZS EV EXCLUSIVE").model)
        assertEquals(CarModel.M9, CarIdentity("M9").model)
        assertEquals(CarModel.Cyberster, CarIdentity("Cyberster").model)
        assertEquals(CarModel.Tomahawk, CarIdentity("Hector Tomahawk EV").model)
    }

    @Test fun `the trim is dropped from the name`() {
        assertEquals(CarIdentity("Comet EV"), identity("COMET EV EXCLUSIVE FC", "E230"))
        assertEquals(CarIdentity("Windsor"), identity("Windsor", "EQ100"))
    }

    @Test fun `a series code alone is enough`() {
        assertEquals(CarIdentity("Comet EV"), identity(null, "EC32"))
        assertEquals(CarIdentity("Windsor"), identity(" ", "EQ100"))
    }

    @Test fun `a Windsor Pro named only by its trim is a Windsor Pro`() {
        assertEquals(CarIdentity("Windsor Pro"), identity("ESSENCE PRO", "EQ200"))
        assertEquals(CarIdentity("Windsor Pro"), identity("Windsor Pro Essence", null))
        assertEquals(CarIdentity("Windsor Pro"), identity("Exclusive Pro", null))
        assertEquals(CarIdentity("Windsor"), identity("Essence", null))
        assertEquals(CarIdentity("Windsor"), identity("EXCLUSIVE", "EQ100"))
    }

    @Test fun `trims only one model sells name it`() {
        assertEquals(CarIdentity("ZS EV"), identity("EXCITE PRO", null))
        assertEquals(CarIdentity("ZS EV"), identity("Exclusive Plus", null))
        assertEquals(CarIdentity("Comet EV"), identity("EXCITE FC", null))
        assertEquals(CarIdentity("Comet EV"), identity("Exclusive FC", null))
        assertEquals(CarIdentity("Comet EV"), identity("PLUSH", null))
    }

    @Test fun `trims several models sell keep MG's name`() {
        assertEquals(CarIdentity("EXECUTIVE"), identity("EXECUTIVE", null))
        assertEquals(CarIdentity("Excite"), identity("Excite", null))
        assertEquals(CarIdentity("Exclusive"), identity("Exclusive", null))
    }

    @Test fun `petrol MGs with Pro trims are not EVs`() {
        assertEquals(CarModel.Other, identity("HECTOR SHARP PRO", null).model)
        assertEquals(CarModel.Other, identity("ASTOR SAVVY PRO", null).model)
    }

    @Test fun `another model's Essence trim stays that model`() {
        assertEquals(CarIdentity("ZS EV"), identity("ZS EV ESSENCE", null))
        assertEquals(CarIdentity("Comet EV"), identity("COMET EV EXCLUSIVE FC", "E230"))
    }

    @Test fun `a name saved before the fix is named again`() {
        assertEquals(CarIdentity("Windsor Pro"), CarIdentity.fromSaved("ESSENCE PRO"))
        assertEquals(CarIdentity("Windsor"), CarIdentity.fromSaved("Windsor"))
        assertEquals(CarIdentity("Windsor Pro"), CarIdentity.fromSaved("Windsor Pro"))
        assertEquals(CarIdentity("Hector"), CarIdentity.fromSaved("Hector"))
        assertEquals(CarIdentity.Unknown, CarIdentity.fromSaved(CarIdentity.Unknown.name))
    }

    @Test fun `the petrol ZS Astor is not the ZS EV`() {
        assertEquals(CarModel.Other, identity("ZS ASTOR VTi-TECH CVT SAVVY RED", "New ZS").model)
    }

    @Test fun `anything else keeps its name without the brand`() {
        assertEquals(CarIdentity("Hector"), identity("MG Hector", null))
        assertEquals(CarModel.Other, CarIdentity("Hector").model)
        assertEquals(CarIdentity.Unknown, identity(null, null))
        assertEquals(CarModel.Other, CarIdentity.Unknown.model)
    }
}
