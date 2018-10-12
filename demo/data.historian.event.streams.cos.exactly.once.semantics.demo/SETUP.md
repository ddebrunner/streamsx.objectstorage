# Setting up the IBM Cloud services for the Data Historian Demo

You'll need:
  * An instance of [IBM COS](https://console.bluemix.net/docs/services/cloud-object-storage/getting-started.html)
  * An instance of [IBM Event Streams](https://console.bluemix.net/docs/services/EventStreams/index.html#getting_started)
  * An instance of [IBM Streaming Analytics](https://console.bluemix.net/docs/services/StreamingAnalytics/index.html#gettingstarted)
  * An instance of [IBM SQL Query](https://console.bluemix.net/docs/services/sql-query/getting-started.html#getting-started-tutorial)

**Service plans for performance use case**:
To achieve with large data volumes high throughput (Streaming Analytics) and guaranteed performance (Event Streams) it is required to select the following service plans:
* Enterprise plan for Event Streams
* Premium Container for Streaming Analytics
* Standard plan for IBM Cloud Object Storage
* Select the **same region** for the services, for example, `us-south` 

**Alternative "Lite" service plans for non-performance use case**:
* Standard plan for Event Streams
* Lite Container for Streaming Analytics
* Lite plan for IBM Cloud Object Storage
* Select the **same region** for the services, for example, `us-south` 

For IBM SQL Query service the lite plan is sufficient.

## Connect Streaming Analytics service with Event Streams and COS

Generate and get the credentials:
  * [generating Event Streams 'service credential'](https://console.bluemix.net/docs/services/MessageHub/messagehub127.html#connecting)
  * [generating COS 'service credential'](https://console.bluemix.net/docs/services/cloud-object-storage/iam/service-credentials.html)

These credentials shall be stored in IBM Streaming Analytics service instance application configuration properties.

### Service credentials for Event Streams

app option name = `messagehub`

property name = `messagehub.creds`

Put the entire JSON string into the property value.

### Service credentials for Cloud Object Storage

app option name = `cos`

property name = `cos.creds`

Put the entire JSON string into the property value.


## Create topics in Event Streams service

When running the **"performance"** scenario, it is recommended to create a topic wiht 6 partitions.

topic name = `dh`

partitions = `6`

When running the **"lite"** scenario, it is recommended to create a topic wiht one partition only.

topic name = `dh_lite`

partitions = `1`


## Create bucket in Cloud Object Storage service

Create a bucket with a unique bucket name, for example `dh-demo001`, `cross-region` for location: `us-geo` with `Standard` Storage class.

## Prepare toolkits

* Download [streamsx.messagehub toolkit](https://github.com/IBMStreams/streamsx.messagehub). For this demo at least version 1.5 is required. You need to build the toolkit, if no pre-built release is available.
* Download [streamsx.objectstorage toolkit](https://github.com/IBMStreams/streamsx.objectstorage). For this demo at least version 1.6 is required. You need to build the toolkit, if no pre-built release is available.


The toolkits containing the demo applications needs to be indexed before launching them with [streamsx-runner](http://ibmstreams.github.io/streamsx.topology/doc/pythondoc/scripts/runner.html) 

    make tkidx

After this toolkit.xml files in `dh_generate_json` and `dh_json_parquet`directories have been generated.
