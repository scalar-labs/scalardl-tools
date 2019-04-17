# Scalar DL Explorer

Scalar DL Explorer is an interactive command line interface to explore and validate assets in a Scalar DL instance.

## Generate an executable

Execute

```bash
./gradlew installDist
```

After a successful run the `explorer` executable may be found in `/build/install/explorer/bin`.

## How to use the command-line interface

The first thing you will need to do is to create a `client.properties` file. The `client.properties` is used to specify the configuration of a Scalar DL server and the user's information. Most likely you have already used Scalar DL, in which case you already have a `client.properties` file. Simply use it here.

By default Scalar DL Explorer looks for the `client.properties` in the project directory. You can use the `-f` option to specify an alternative file.

### Commands

Scalar DL Explorer has four commands:

- `explorer get`: used to retrieve the current value of the specified asset id
- `explorer scan`: used to retrieve the history of the specified asset id
- `explorer validate`: used to validate if the value of the specified asset id has been tampered or not
- `explorer list-contracts`: used to list all the registered contracts

### Help

To get help with any of the commands pass the `-h` option to the command, e.g. `explorer get -h`. To get help with explorer in general pass the `-h` option as in `explorer -h`. 

## Any questions?

If you have any questions please [contact us](https://scalar-labs.com/contact_us/).

## License

Scalar DL Explorer is dual-licensed under both the AGPL (found in the [LICENSE](./LICENSE) file in the root directory) and a commercial license. You may select, at your option, one of the above-listed licenses. Regarding the commercial license, please [contact us](https://scalar-labs.com/contact_us/) for more information.
