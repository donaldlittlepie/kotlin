// IS_APPLICABLE: false
// ERROR: 'infix' modifier is inapplicable on this function

class Foo {
    infix fun foo(x: Int = 0, y: Int = 0) {
    }
}

fun bar(baz: Foo) {
    baz.<caret>foo(y = 1)
}
