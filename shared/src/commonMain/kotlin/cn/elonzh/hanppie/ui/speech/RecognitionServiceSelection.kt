package cn.elonzh.hanppie.ui.speech

/** Resolve a component once, never retry recording through another provider. */
internal fun recognitionServiceSelection(preferred: String?, systemDefault: String?, available: List<String>): String? =
    if (preferred != null) preferred.takeIf { it in available }
    else systemDefault?.takeIf { it in available } ?: available.singleOrNull()
