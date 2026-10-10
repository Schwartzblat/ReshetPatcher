import re

from stitch.artifactory_generator.SimpleArtifactoryFinder import SimpleArtifactoryFinder, CLASS_NAME_RE


class TheoImaIntegrationFinder(SimpleArtifactoryFinder):
    """THEOplayer's GoogleImaIntegration implementation (an obfuscated vendor class).

    Every client-side IMA ad in the C13 player is registered through its
    schedule(GoogleImaAdDescription): setSource() loops over source.ads and calls
    it, and the RN AdsModule.schedule() path ends there too. The method name is
    fixed by the public GoogleImaIntegration interface; only the class is renamed.
    """
    SCHEDULE_RE = re.compile(
        r'^\.method public (?:final )?(?P<method_name>schedule)'
        r'(?P<sig>\(Lcom/theoplayer/android/api/source/addescription/GoogleImaAdDescription;\)V)'
        r'(?:(?!\.end method)[\s\S])*?"googleImaAdDescription"',
        re.M)

    def __init__(self, args):
        super().__init__(args)
        self.is_once = True
        self.is_found = False

    def class_filter(self, class_data: str) -> bool:
        return '.implements Lcom/theoplayer/android/api/ads/ima/GoogleImaIntegration;' in class_data

    def extract_artifacts(self, artifacts: dict, class_data: str) -> None:
        matches = list(self.SCHEDULE_RE.finditer(class_data))
        if len(matches) != 1:
            return
        artifacts['THEO_IMA_INTEGRATION_CLASS_NAME'] = \
            CLASS_NAME_RE.match(class_data).groupdict().get('name').replace('/', '.')
        artifacts['THEO_IMA_SCHEDULE_METHOD_NAME'] = matches[0].groupdict().get('method_name')
        artifacts['THEO_IMA_SCHEDULE_METHOD_SIG'] = matches[0].groupdict().get('sig')
        self.is_found = True
