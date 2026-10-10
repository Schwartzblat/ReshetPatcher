r"""Signature finders for ReshetPatcher.

Reshet's ad code is obfuscated and its class/method names move between
releases, so the hooks never name an obfuscated class. A finder discovers each
one at patch time from the extracted smali and emits {{KEY}} -> value pairs
that stitch substitutes into smali_generator/ before compiling it.

One finder per concept, one module per finder, in this package. Register each
one in main.py's artifactory_list, then run the skill's
validate-artifactory.py gate before building: a finder that matches nothing
leaves {{KEY}} in the Java source, which compiles and then silently fails to
hook at runtime.

EXAMPLE ONLY. It is kept in this docstring on purpose: a real module here is
picked up by validate-artifactory.py, and an example that never fires would
fail the gate. Copy it into its own module (e.g. ad_manager.py) and adjust:

    import re
    from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder, CLASS_NAME_RE


    class ExampleFinder(SimpleArtifactoryFinder):
        # Pin the method by shape, never by name.
        METHOD_RE = re.compile(
            r'\.method public final (?P<method_name>\w+)(?P<sig>\(\)Z)(?:(?!end method)[\s\S])*?some_anchor')

        def __init__(self, args):
            super().__init__(args)
            self.is_once = True
            self.is_found = False

        def class_filter(self, class_data: str) -> bool:
            # Anchor on a string literal or framework reference the app cannot rename.
            return '"some_stable_string"' in class_data

        def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
            matches = list(self.METHOD_RE.finditer(class_data))
            if len(matches) != 1:
                return  # ambiguity means do not fire
            artifacts['EXAMPLE_CLASS_NAME'] = \
                CLASS_NAME_RE.match(class_data).groupdict().get('name').replace('/', '.')
            artifacts['EXAMPLE_METHOD_NAME'] = matches[0].groupdict().get('method_name')
            artifacts['EXAMPLE_METHOD_SIG'] = matches[0].groupdict().get('sig')
            self.is_found = True

The hook in smali_generator/app/src/main/java/com/smali_generator/patches/
then reads "{{EXAMPLE_CLASS_NAME}}", "{{EXAMPLE_METHOD_NAME}}" and
"{{EXAMPLE_METHOD_SIG}}" and is registered in InitProviderReshet.hooks.
"""
