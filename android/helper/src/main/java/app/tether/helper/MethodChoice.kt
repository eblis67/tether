package app.tether.helper

import org.json.JSONObject

/**
 * What the phone remembers about its call-audio methods (docs/CALL-AUDIO.md), one record per phone model,
 * stored by the app as JSON. Pure rules: the app reads a [State], asks what to start with, and writes back
 * what the self-test and the user taught it.
 *
 * Three teachers, in the order a call consults them:
 * - the **user's pick** (the settings picker): wins over everything, per direction;
 * - the **self-test** ([chosen], machine-verified with a tone in the uplink): the automatic choice while the
 *   user has not voted it down;
 * - the **user's feedback** ("could they hear you?" after a test during a call): a method with more
 *   "not heard" than "heard" votes loses its [chosen] place and is skipped by the `auto` ladder, so the next
 *   call moves on. A method the self-test verifies afterwards has its "not heard" votes cleared — the tone
 *   in the uplink is stronger evidence than one bad call.
 */
object MethodChoice {
    data class State(
        val manualInject: String? = null,
        val manualCapture: String? = null,
        val chosenInject: String? = null,
        val chosenCapture: String? = null,
        /** Last self-test verdict per method, keyed `dir:id` (ids repeat across directions: `intercept`). */
        val tested: Map<String, Boolean> = emptyMap(),
        /** The user's "the other side heard / heard the client" votes per method, keyed `dir:id`. */
        val heard: Map<String, Int> = emptyMap(),
        val notHeard: Map<String, Int> = emptyMap(),
    ) {
        fun manual(dir: AudioMethods.Dir): String? = if (dir == AudioMethods.Dir.INJECT) manualInject else manualCapture
        fun chosen(dir: AudioMethods.Dir): String? = if (dir == AudioMethods.Dir.INJECT) chosenInject else chosenCapture
    }

    fun key(dir: AudioMethods.Dir, id: String): String = "${dir.wire}:$id"

    // ------------------------------------------------------------------ storage

    fun parse(o: JSONObject?): State {
        if (o == null) return State()
        fun counts(name: String): Map<String, Int> {
            val m = o.optJSONObject(name) ?: return emptyMap()
            return m.keys().asSequence().associateWith { m.optInt(it) }.filterValues { it > 0 }
        }
        val t = o.optJSONObject("tested")
        return State(
            manualInject = o.optString("manualInject").takeIf { it.isNotEmpty() },
            manualCapture = o.optString("manualCapture").takeIf { it.isNotEmpty() },
            chosenInject = o.optString("chosenInject").takeIf { it.isNotEmpty() },
            chosenCapture = o.optString("chosenCapture").takeIf { it.isNotEmpty() },
            tested = t?.keys()?.asSequence()?.associateWith { t.optBoolean(it) } ?: emptyMap(),
            heard = counts("heard"),
            notHeard = counts("notHeard"),
        )
    }

    fun toJson(s: State): JSONObject = JSONObject().apply {
        s.manualInject?.let { put("manualInject", it) }
        s.manualCapture?.let { put("manualCapture", it) }
        s.chosenInject?.let { put("chosenInject", it) }
        s.chosenCapture?.let { put("chosenCapture", it) }
        if (s.tested.isNotEmpty()) put("tested", JSONObject().apply { s.tested.forEach { (k, v) -> put(k, v) } })
        if (s.heard.isNotEmpty()) put("heard", JSONObject().apply { s.heard.forEach { (k, v) -> put(k, v) } })
        if (s.notHeard.isNotEmpty()) put("notHeard", JSONObject().apply { s.notHeard.forEach { (k, v) -> put(k, v) } })
    }

    // ------------------------------------------------------------------ selection

    /** The user's verdict on one method: more "not heard" than "heard" — it opens, but does not get through. */
    fun failed(s: State, dir: AudioMethods.Dir, id: String): Boolean =
        (s.notHeard[key(dir, id)] ?: 0) > (s.heard[key(dir, id)] ?: 0)

    /**
     * What to start one direction with: the user's pick, else the self-test's choice while it is still offered
     * on [sdk] and not voted down, else "auto" (the ladder, with [skip] applied by the helper).
     */
    fun pick(sdk: Int, s: State, dir: AudioMethods.Dir): String {
        val ladder = AudioMethods.ladder(dir, sdk).map { it.id }
        s.manual(dir)?.takeIf { it in ladder }?.let { return it }
        s.chosen(dir)?.takeIf { it in ladder && !failed(s, dir, it) }?.let { return it }
        return "auto"
    }

    /**
     * The methods `auto` must not try on [sdk]: the ones the user voted down. Never all of them — when every
     * route is voted down the vote was probably about the far end, so the full ladder runs. Empty whenever a
     * manual pick or a surviving [State.chosen] decides instead of the ladder.
     */
    fun skip(sdk: Int, s: State, dir: AudioMethods.Dir): List<String> {
        if (pick(sdk, s, dir) != "auto") return emptyList()
        val ladder = AudioMethods.ladder(dir, sdk).map { it.id }
        val down = ladder.filter { failed(s, dir, it) }
        return if (down.size >= ladder.size) emptyList() else down
    }

    // ------------------------------------------------------------------ learning

    /** The user's pick for one direction ([id] null or "auto" clears it); an unknown id is ignored. */
    fun withManual(s: State, sdk: Int, dir: AudioMethods.Dir, id: String?): State {
        val clean = id?.takeIf { it != "auto" && AudioMethods.byId(dir, it) != null && AudioMethods.ladder(dir, sdk).any { m -> m.id == it } }
        return if (dir == AudioMethods.Dir.INJECT) s.copy(manualInject = clean) else s.copy(manualCapture = clean)
    }

    /**
     * The self-test's results: per-method verdicts ([injectOk] / [captureOk] by id) and its winners. A method
     * the test verified has its "not heard" votes cleared (the tone is stronger evidence than one bad call).
     */
    fun recordSelftest(s: State, injectOk: Map<String, Boolean>, captureOk: Map<String, Boolean>,
                       chosenInject: String?, chosenCapture: String?): State {
        val tested = s.tested.toMutableMap()
        val notHeard = s.notHeard.toMutableMap()
        fun learn(dir: AudioMethods.Dir, ok: Map<String, Boolean>) {
            for ((id, good) in ok) {
                if (AudioMethods.byId(dir, id) == null) continue
                tested[key(dir, id)] = good
                if (good) notHeard.remove(key(dir, id))
            }
        }
        learn(AudioMethods.Dir.INJECT, injectOk)
        learn(AudioMethods.Dir.CAPTURE, captureOk)
        return s.copy(
            chosenInject = chosenInject?.takeIf { AudioMethods.byId(AudioMethods.Dir.INJECT, it) != null } ?: s.chosenInject,
            chosenCapture = chosenCapture?.takeIf { AudioMethods.byId(AudioMethods.Dir.CAPTURE, it) != null } ?: s.chosenCapture,
            tested = tested, notHeard = notHeard)
    }

    /**
     * The user's answers after a test during a call, attributed by the phone to the methods the call actually
     * used: [heardYou] votes on [injectId] (could the other side hear the client), [heardThem] on [captureId]
     * (could the client hear the other side). A "not heard" also drops the method's [State.chosen] place, so
     * the next call re-picks at once instead of waiting for the vote count to tip.
     */
    fun recordFeedback(s: State, injectId: String?, captureId: String?, heardYou: Boolean, heardThem: Boolean): State {
        var out = s
        fun vote(dir: AudioMethods.Dir, id: String?, heardIt: Boolean) {
            if (id == null || AudioMethods.byId(dir, id) == null) return
            val k = key(dir, id)
            out = if (heardIt) out.copy(heard = out.heard + (k to ((out.heard[k] ?: 0) + 1)))
            else out.copy(notHeard = out.notHeard + (k to ((out.notHeard[k] ?: 0) + 1)),
                chosenInject = if (dir == AudioMethods.Dir.INJECT && out.chosenInject == id) null else out.chosenInject,
                chosenCapture = if (dir == AudioMethods.Dir.CAPTURE && out.chosenCapture == id) null else out.chosenCapture)
        }
        vote(AudioMethods.Dir.INJECT, injectId, heardYou)
        vote(AudioMethods.Dir.CAPTURE, captureId, heardThem)
        return out
    }
}
