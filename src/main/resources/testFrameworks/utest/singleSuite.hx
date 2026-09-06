// Entry point the IDE generates for a gutter-started run: one utest case
// class from the tests build, compiled with that build's classpaths, defines
// and libraries. A single-test run rides on top via -D UTEST_PATTERN. The
// ${NEW_SUITES} token (one `new Suite()` per selected class) is substituted before the compile; this file is a
// template, never compiled as-is.
class IjSingleRun {
	static function main() {
		utest.UTest.run([${NEW_SUITES}]);
	}
}
