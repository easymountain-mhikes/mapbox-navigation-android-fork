import os
import re

import git

pr_number = os.environ['PR_NUMBER']
token = os.environ['GITHUB_TOKEN']

pattern = re.compile("^\d*.md")


def rename_files(path):
    renamed_files_count = 0
    files = os.listdir(path)

    new_md_files = list(filter(lambda file: not pattern.match(file), files))

    if len(new_md_files) > 1:
        raise Exception('More than one new changelog file')

    for file in new_md_files:
        if not pattern.match(file):
            os.rename(path + file, path + pr_number + '.md')
            renamed_files_count += 1

    return renamed_files_count


renamed_bugfixes_count = rename_files('changelog/unreleased/bugfixes/')
renamed_features_count = rename_files('changelog/unreleased/features/')

if renamed_features_count + renamed_bugfixes_count > 0:
    repository = git.Repo('.')
    repository.git.add('changelog/unreleased')
    repository.index.commit('Rename changelog files')
    config = open('.git/config', 'r').read()
    print(config)
    updated_config = config.replace('git@github.com:mapbox/mapbox-navigation-android.git',
                                    'https://x-access-token:' + token + '@github.com/mapbox/test-nav-sdk')
    open('.git/config', 'w').write(updated_config)
    repository.remotes.origin.push().raise_if_error()
