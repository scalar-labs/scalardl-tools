# Scalar DL Emulator

Scalar DL Emulator is an interactive command line interface to run Scalar DL on a local mutable in-memory ledger and database. The emulator may be used to quickly and easily test Scalar DL contracts. It does, however, lack any of the tamper-evident features found in the actual Scalar DL.

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
this will run the commands contained in [cmds.txt](./cmds.txt)

```bash
./build/install/emulator/bin/emulator -f cmds.txt
```

Exit the emulator with `exit` or by ctrl-d (EOF).

## Preregistered contracts

 There are three predefined and preregistered contracts, with corresponding commands, to `put`, `get`, and `scan` assets. These contracts may be found in the `contract` subdirectory

 ```
 src/main/java/com/scalar/client/tool/emulator/contract
 ```

## Build a contract

Write a contract and save it in the `contract` subdirectory

 ```
 src/main/java/com/scalar/client/tool/emulator/contract
 ```

Run `./gradlew build` to compile the contract.

## Register a contract

`register` command will reigister the specified contract with the specified contract id and the binary name.

 For example, the contract `com.scalar.client.tool.emulator.contract.StateUpdater.java` with id `state-updater-contract` can be registered as follows.

```
scalar> register state-updater-contract com.scalar.client.tool.emulator.contract.StateUpdater ./build/classes/java/main/com/scalar/client/tool/emulator/contract/StateUpdater.class
```

## Exexute a contract

`execute` command will excute the specified contract.

For example, the `state-updater-contract` can be executed as follows.

```
scalar> execute state-updater-contract {"asset_id": "Y", "state": 1}
```

## Register a user-defined function (UDF)

UDF is a business logic to update the mutable database in the Scalar DL network.
`register-function` command will register the the specified function with the specified id and the binary name.

For example, the UDF `com.scalar.client.tool.emulator.function.StateUpdater` with id `state-updater-function` can be registered as follows.

```
scalar> register-function state-updater-function com.scalar.client.tool.emulator.function.StateUpdater.java ./build/classes/java/main/com/scalar/client/tool/emulator/function/StateUpdater.class
```

## Execute a UDF

Registered UDFs can only be executed with a register contract and what UDFs to execute can be specified with `_functions_` key in the contract argument.

For example, a UDF with id `state-updater-function` can be executed with a contrat with id `state-updater-contract` as follows.

```
scalar> execute state-updater-contract {"argument_to_contract":"argument_value","_functions_":["state-updater-function"]} -fa {"argument_to_udf":"argument_value"}
```

NOTE:
You can use `database` command to see what is stored by UDFs.

## Help

Type `help` to display the list of available commands inside the interactive terminal.

```
scalar> help
Available commands:
 - database
 - execute
 - get
 - get -j
 - list-contracts
 - put
 - put -j
 - register
 - register-function
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
execute [-h] [-fa=<function argument>] id argument...

Description:
Execute a registered contract.

Parameters:
      id            contract id. Use 'list-contracts' to list all the registered contracts and their ids.
      argument...   the JSON contract argument. A plain text JSON object or the path to a file containing a JSON object

Options:
  -h, --help        print the help and exit
    -fa, --function_argument=<functionArgument>
        the argument passed to UDF

For example: 'execute get {"asset_id": "foo"}'
```

## Command history

A history of executed commands is saved to `.scalardl_emulator_history` in your home directory.

## Any questions?

If you have any questions please [contact us](https://scalar-labs.com/contact_us/).

## License

Scalar DL Emulator is dual-licensed under both the AGPL (found in the [LICENSE](./LICENSE) file in the root directory) and a commercial license. You may select, at your option, one of the above-listed licenses. Regarding the commercial license, please [contact us](https://scalar-labs.com/contact_us/) for more information.
