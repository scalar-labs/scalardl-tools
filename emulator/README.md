# Scalar DL Emulator

Scalar DL Emulator is an interactive command line interface to run Scalar DL on a local mutable in-memory ledger. The emulator may be used to quickly and easily test Scalar DL contracts. It does, however, lack any of the tamper-evident features found in the actual Scalar DL.

## Generate an executable

Execute

```bash
./gradlew installDist
```

After a successful run the `emulator` executable may be found in `/build/install/emulator/bin`.

## Run

To run the emulator

```bash
./build/install/emulator/bin/emulator
```

or pass a file with `-f` containing a list of commands the emulator will execute. For example,
this will run the commands contained in [cmds.txt](https://github.com/scalar-labs/scalardl-emulator/blob/master/cmds.txt)

```bash
./build/install/emulator/bin/emulator -f cmds.txt
```

## Exit the emulator

Exit the emulator with `exit` or by ctrl-d (EOF).

## Preregistered contracts

 There are three predefined and preregistered contracts, with corresponding commands, to `put`, `get`, and `scan` assets. These contracts may be found in the `contract` subdirectory
 
 ```
 src/main/java/com/scalar/client/tool/emulator/contact
 ```

## Register a contract

Write a contract and save it in the `contract` subdirectory
 
 ```
 src/main/java/com/scalar/client/tool/emulator/contact
 ```

Run `./gradlew build` to compile the contract. Then start the emulator and register the contract by using the `register` command. For example, to register the contract `StateUpdater.java` with id `state-updater`

```
scalar> register state-updater com.scalar.client.tool.emulator.contract.StateUpdater ./build/classes/java/main/com/scalar/client/tool/emulator/contract/StateUpdater.class
```

Now this contract may be executed, for example, as

```
scalar> execute state-updater {"asset_id": "Y", "state": 1}"
```

## Help

Type `help` to display the list of available commands inside the interactive terminal.

```
scalar> help
Available commands:
 - execute
 - get
 - get -j
 - list-contracts
 - put
 - put -j
 - register
 - scan
 - scan -j
 - help
 - exit
Type '<command> -h' to display help for the command.
```

Every command has a detailed help that can be displayed with `-h`. For example:

```
scalar> execute -h

Usage:
execute [-h] id argument...

Description:
Execute a registered contract.

Parameters:
      id            contract id. Use 'list-contracts' to list all the registered contracts and their ids.
      argument...   the JSON contract argument. A plain text JSON object or the path to a file containing a JSON object

Options:
  -h, --help        print the help and exit

For example: 'execute get {"asset_id": "foo"}'
```

## Command history

A history of executed commands is saved to `.scalardl_emulator_history` in your home directory.

## Any questions?

If you have any questions please [contact us](https://scalar-labs.com/contact_us/).

## License

Scalar DL Emulator is dual-licensed under both the AGPL (found in the [LICENSE](./LICENSE) file in the root directory) and a commercial license. You may select, at your option, one of the above-listed licenses. Regarding the commercial license, please [contact us](https://scalar-labs.com/contact_us/) for more information.
