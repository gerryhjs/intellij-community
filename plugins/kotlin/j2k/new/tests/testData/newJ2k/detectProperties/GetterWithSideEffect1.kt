class C {
    private var myX = ""

    var x: String
        get() {
            println("getter invoked")
            return myX
        }
        set(x) {
            myX = x
        }

    fun foo() {
        println("myX = $myX")
    }
}