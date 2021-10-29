package com.mapbox.navigation.core.telemetry.events

import android.annotation.SuppressLint
import com.mapbox.bindgen.Value
import com.mapbox.navigation.base.metrics.NavigationMetrics

@SuppressLint("ParcelCreator")
internal class NavigationCancelEvent(
    phoneState: PhoneState
) : NavigationEvent(phoneState) {
    /*
     * Don't remove any fields, cause they should match with
     * the schema downloaded from S3. Look at {@link SchemaTest}
     */
    var arrivalTimestamp: String? = null
    var rating: Int = 0
    var comment: String = ""

    override fun getEventName(): String = NavigationMetrics.CANCEL_SESSION

    override fun toValue(): Value {
        val value = super.toValue()

        val fields = hashMapOf<String, Value>()
        value.contents?.let {
            it as HashMap<String, Value>
            fields.putAll(it)
        }

        arrivalTimestamp?.let { fields["arrivalTimestamp"] = it.toValue() }
        fields["rating"] = rating.toValue()
        fields["comment"] = comment.toValue()

        return value
    }
}
