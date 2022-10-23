package com.klipper4a.ui.views

import android.animation.LayoutTransition
import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isGone
import com.klipper4a.R
import com.klipper4a.repository.KlipperServerStatus
import com.klipper4a.utils.animatedAlpha
import com.klipper4a.utils.getArchString
import kotlinx.android.synthetic.main.view_installation_item.view.*

class InstallationProgressItem @JvmOverloads
constructor(private val ctx: Context, private val attributeSet: AttributeSet? = null, private val defStyleAttr: Int = 0)
    : ConstraintLayout(ctx, attributeSet, defStyleAttr) {

    init {
        val inflater = ctx.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        inflater.inflate(R.layout.view_installation_item, this)
        layoutTransition = LayoutTransition()
    }

    var status: KlipperServerStatus
        get() = KlipperServerStatus.Stopped
        set(value) {
            contentTextView.text = when (value) {
                KlipperServerStatus.InstallingBootstrap -> resources.getString(R.string.installation_step_bootstrap, getArchString())
                KlipperServerStatus.InstallingKlipper -> resources.getString(R.string.installation_step_klipper)
                KlipperServerStatus.InstallingMoonraker -> resources.getString(R.string.installation_step_moonraker)
                KlipperServerStatus.InstallingMainsail -> resources.getString(R.string.installation_step_mainsail)

                KlipperServerStatus.InstalledBootstrap -> resources.getString(R.string.installation_step_bootstrap_done, getArchString())
                KlipperServerStatus.InstalledKlipper -> resources.getString(R.string.installation_step_klipper_done)
                KlipperServerStatus.InstalledMoonraker -> resources.getString(R.string.installation_step_moonraker_done)
                KlipperServerStatus.InstalledMainsail -> resources.getString(R.string.installation_step_mainsail_done)

                KlipperServerStatus.BootingUp -> resources.getString(R.string.installation_step_bootup)
                KlipperServerStatus.Running -> resources.getString(R.string.installation_step_done)
                else -> "Unknown status"
            }
        }

    var isLoading: Boolean
        get() = false
        set(value) {
            spinnerView.isGone = !value
            doneIconView.isGone = value
            if (!value) {
                contentTextView.animatedAlpha = 0.4F
                contentTextView.typeface = Typeface.DEFAULT
            } else {
                contentTextView.animatedAlpha = 1F
                contentTextView.typeface = Typeface.DEFAULT_BOLD
            }
        }

    fun setUpcoming() {
        spinnerView.isGone = true
        doneIconView.isGone = true
        contentTextView.animatedAlpha = 0.4F
        contentTextView.typeface = Typeface.DEFAULT
    }

}