package de.jonasgroll.spotr

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Kachel in den Schnelleinstellungen: Hotspot an oder aus, ohne die App zu öffnen. */
class Kachel : TileService() {
    override fun onStartListening() = zeigen(Hotspot.istAn(this))

    // Vom Sperrbildschirm aus erst entsperren, sonst könnte jeder mit dem Handy in der Hand schalten.
    override fun onClick() {
        if (isLocked && isSecure) unlockAndRun { schalten() } else schalten()
    }

    private fun schalten() {
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
