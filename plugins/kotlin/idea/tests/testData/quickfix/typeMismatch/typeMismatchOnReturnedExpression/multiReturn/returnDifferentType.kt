// "Change return type of enclosing function 'test' to 'Any'" "true"
fun test(x: Int) {
    if (true) return "foo"<caret>
    return x
}

/* IGNORE_K2 */


// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.ChangeCallableReturnTypeFix$ForEnclosing