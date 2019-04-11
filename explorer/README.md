# Scalar DL Explorer

Scalar DL Explorer is an interactive command line interface to explore and validate assets in a Scalar DL instance.

## Generate an executable

Execute

```bash
./gradlew installDist
```

After a successful run the `explorer` executable may be found in `/build/install/explorer/bin`.

## How to use the command-line interface

The first thing you will need to do is to edit the [client.properties](./conf/client.properties) file (found in the `conf` directory) to suit your setup. `client.properties` is a configuration of Scalar server's and user's information. Scalar DL Explorer uses it to connect to a specified Scalar server and keep track of the user's private key.

By default Scalar DL Explorer looks for the `client.properties` in the `conf` directory. You can use the `-f` option to specify a particular file.

### Commands

Scalar DL Explorer has four commands:

- `explorer get`: used to retrieve the current value of the specified asset id
- `explorer scan`: used to retrieve the history of the specified asset id
- `explorer validate`: used to validate if the value of the specified asset id has been tampered or not
- `explorer list-contracts`: used to list all the registered contracts for given holder id

To get help with any of the commands simple pass the the `-h` option to `explorer` or any of the commands, e.g. `explorer get -h`.

## Any questions?

If you have any questions please [contact us](https://scalar-labs.com/contact_us/).

## License

Scalar DL Emulator is dual-licensed under both the AGPL (found in the [LICENSE](https://github.com/scalar-labs/scalardl-emulator/blob/master/LICENSE) file in the root directory) and a commercial license. You may select, at your option, one of the above-listed licenses. Regarding the commercial license, please [contact us](https://scalar-labs.com/contact_us/) for more information.
