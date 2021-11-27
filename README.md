# Assistant

A Discord bot to assist my everyday needs.

[Invite (Private)](https://discord.com/api/oauth2/authorize?client_id=856158596344709130&permissions=8192&scope=applications.commands%20bot)

## Installing

[Clojure](https://clojure.org/guides/getting_started) is required to build Assistant. After Clojure has been installed,
[download the project](https://github.com/KyleErhabor/assistant/releases/latest) and run `clj -T:build uber` to build
it. This may take a while to complete (e.g. a minute).

### Running

In the top-level directory of the project, run:
```sh
java -jar target/assistant-<version>-standalone.jar <token>
```

`<version>` must be the version of the project built, which can be found in the `target` directory. `<token>` must be
the [Discord bot token](https://discord.com/developers/applications) to authenticate with.

## License

Copyright © 2021 Kyle Erhabor

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this software except in compliance with
the License. You may obtain a copy of the License in [LICENSE.txt](./LICENSE.txt).

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific
language governing permissions and limitations under the License.
