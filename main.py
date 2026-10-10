import argparse
from pathlib import Path

from stitch import Stitch
from stitch.common import ExternalModule

# Signature finders live in ./artifactory_generator/ and are registered in
# artifactory_list below. See artifactory_generator/__init__.py for the contract.
from artifactory_generator.theo_ima_integration import TheoImaIntegrationFinder

# Stitch writes this into the target's manifest as the provider's android:name.
# It must match the class in smali_generator/app/src/main/java/com/smali_generator/.
PROVIDER_CLASS = 'com.smali_generator.InitProviderReshet'

# Stitch writes this into the target's manifest as the provider's android:name,
# and the paywall module generates a class of that name from PAYWALL_PROVIDER_CLASS.
# Both are derived from this one constant so they cannot drift: a manifest naming
# a class that is not in the dex installs fine and dies at launch.
PAYWALL_PROVIDER = 'com.paywall.InitProviderPaywallReshet'


def get_args():
    parser = argparse.ArgumentParser(description='')
    parser.add_argument('-p', '--apk-path', dest='apk_path', help='APK path', required=True)
    parser.add_argument('-o', '--output', dest='output', help='Output APK path', required=False, default='output.apk')
    parser.add_argument('-t', '--temp', dest='temp_path', help='Temp path for extracted content', required=False,
                        default='./temp')
    parser.add_argument('--arch', dest='arch', help='ABI whose libarthooks.so gets injected', required=False,
                        default='arm64-v8a', choices=['arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86'])
    parser.add_argument('--artifactory', dest='artifactory', help='Artifactory path (unused by stitch)',
                        required=False, default='./artifactory.json')
    parser.add_argument('--no-sign', dest='should_sign', help='Whether to sign the output APK', action='store_false',
                        required=False, default=True)
    parser.add_argument('--extra-artifacts', dest='extra_artifacts',
                        help='Extra artifact to add to the artifactory, in the format "key:value"',
                        required=False, default=[], nargs='+')
    parser.add_argument('--paywall', dest='paywall', help='Whether to add the paywall patch', required=False,
                        default=None)
    args, _ = parser.parse_known_args()
    return args


def main():
    args = get_args()
    extra_artifacts = {artifact.split(':', 1)[0]: artifact.split(':', 1)[1] for artifact in args.extra_artifacts}
    external_modules = [
        ExternalModule(Path(__file__).parent / './smali_generator', PROVIDER_CLASS)
    ]
    if args.paywall is not None:
        extra_artifacts.setdefault('PAYWALL_PROVIDER_CLASS', PAYWALL_PROVIDER.rsplit('.', 1)[1])
        external_modules.append(ExternalModule(Path(args.paywall), PAYWALL_PROVIDER))
    artifactory_list = [
        TheoImaIntegrationFinder(args),
    ]
    with Stitch(
            apk_path=args.apk_path,
            output_apk=args.output,
            temp_path=args.temp_path,
            artifactory_list=artifactory_list,
            external_modules=external_modules,
            arch=args.arch,
            should_sign=args.should_sign,
            extra_artifacts=extra_artifacts
    ) as stitch:
        stitch.patch()


if __name__ == '__main__':
    main()
