package de.jonasgroll.spotr

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Kachel in den Schnelleinstellungen: Hotspot an oder aus, ohne die App zu öffnen. */
class Kachel : TileService() {
    override fun onStartListening() = zeigen(Hotspot.istAn(this))

    override fun onClick() {
        val an = !Hotspot.istAn(this)
        zeigen(an)
        Thread {
            val fehler = Hotspot.setzen(this, an)
            Speicher(this).notieren(
                if (fehler == null) (if (an) "eingeschaltet (Kachel)" else "ausgeschaltet (Kachel)") else "Kachel: $fehler",
            )
            Thread.sleep(1500)
            zeigen(Hotspot.istAn(this))
        }.start()
    }

    private fun zeigen(an: Boolean) {
        val t = qsTile ?: return
        t.state = if (an) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.subtitle = if (an) "An" else "Aus"
        t.updateTile()
    }
}
