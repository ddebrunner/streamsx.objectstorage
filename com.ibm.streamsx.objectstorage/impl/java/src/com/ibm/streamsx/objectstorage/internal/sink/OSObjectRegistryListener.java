package com.ibm.streamsx.objectstorage.internal.sink;

import java.util.logging.Logger;

import org.ehcache.event.CacheEvent;
import org.ehcache.event.CacheEventListener;

import com.ibm.streams.operator.logging.LoggerNames;
import com.ibm.streams.operator.logging.TraceLevel;
import com.ibm.streamsx.objectstorage.BaseObjectStorageSink;
import com.ibm.streamsx.objectstorage.Messages;

/**
 * Listener for cache lifecycle events
 * @author streamsadmin
 *
 */
public class OSObjectRegistryListener implements CacheEventListener<String, OSObject> {
	
	private BaseObjectStorageSink fParent;
	
	private static final String CLASS_NAME = OSObjectRegistryListener.class.getName();
	
	private static Logger TRACE = Logger.getLogger(CLASS_NAME);
		
	private static Logger LOGGER = Logger.getLogger(LoggerNames.LOG_FACILITY + "." + CLASS_NAME);

	private boolean enableTrhresholdNotification = true;
	
	public OSObjectRegistryListener(BaseObjectStorageSink parent) {
		fParent = parent;
	}

	@Override
	public void onEvent(CacheEvent<? extends String, ? extends OSObject> event)  {
		OSObject osObject = event.getOldValue();
		
		if (TRACE.isLoggable(TraceLevel.TRACE)) {			
			String objStr = "";
			if (osObject != null) {
				objStr = osObject.toString();
			}
			TRACE.log(TraceLevel.TRACE,	"Event received for partition '" + event.getKey() + "' of type '" + event.getType() + "' " + objStr);
		}		
		// when active objects number is less than number of allowed concurrent partitions then
		// allow notifications generation.
		// this prevents repeatable notifications generation when active objects number
		// is greater than maximum number of concurrent partitions
		if (fParent.getActiveObjectsMetric().getValue() < fParent.getMaxConcurrentPartitionsNumMetric().getValue()) { 
			enableTrhresholdNotification = true;
		} 			
		
		switch (event.getType()) {
		case CREATED: // new entry added
			fParent.getActiveObjectsMetric().increment();
			break;
		
		case REMOVED: // entry has been removed
			writeObject(osObject);
			break;		
		case EXPIRED: // OSObject is expired according to Expiry
			          // derived from operator rolling policy 
			writeObject(osObject);
			break;
		case EVICTED: // no space left for new entries
			writeObject(osObject);
			// active objects number is greater than max concurrent partitions -  happens 
			// for partitions case only
			if ((fParent.getActiveObjectsMetric().getValue() > fParent.getMaxConcurrentPartitionsNumMetric().getValue()) && enableTrhresholdNotification) {
				String generalWarningMsg = Messages.getString("OBJECTSTORAGE_SINK_MAX_PARTITIONS_COUNT_REACHED", 													
														fParent.getActiveObjectsMetric().getValue(), 
														fParent.getMaxConcurrentPartitionsNumMetric().getValue());
	
				// Should be WARNING - NOT ERROR 
				//if (TRACE.isLoggable(TraceLevel.WARNING)) {
				//	TRACE.log(TraceLevel.WARNING,	generalWarningMsg);
				//}
				if (TRACE.isLoggable(TraceLevel.ERROR)) {
					TRACE.log(TraceLevel.ERROR,	generalWarningMsg);
				}
		    	LOGGER.log(TraceLevel.WARNING, generalWarningMsg);
		    	enableTrhresholdNotification = false;
			}
			// warn about closing object ahead of time
			String objectSpecificWarningMsg = Messages.getString("OBJECTSTORAGE_SINK_OBJECT_CLOSED_PRIOR_RP_EXPIRY", osObject.getPath());
			if (TRACE.isLoggable(TraceLevel.WARNING)) {
				TRACE.log(TraceLevel.WARNING,	objectSpecificWarningMsg);
			}
	    	LOGGER.log(TraceLevel.WARNING, objectSpecificWarningMsg);
		    break;			
		default:
			String errMsg = "Unknown event type '" + event.getType() + "' for key '" + event.getKey() + "' has been received.";
			LOGGER.log(TraceLevel.ERROR, Messages.getString(errMsg));
			break;
		}
	}

	
	private void writeObject(OSObject osObject) {
		if (fParent.isShutdown()) {
			return; // do not write if shutdown is requested
		}
		try {
			// create writable OSObject
			OSWritableObject writableObject = osObject.isWritable() ? 
															(OSWritableObject)osObject : 
															new OSWritableObject(osObject, fParent.getOperatorContext(), fParent.getObjectStorageClient(), fParent.getParquetSchemaStr(), fParent.getParquetWriterConfig());

			if (!writableObject.isClosed()) {
				if (!fParent.isResetting()) {
					// flush buffer
					if (TRACE.isLoggable(TraceLevel.TRACE)) {
						TRACE.log(TraceLevel.TRACE, "flushBuffer: "+ osObject.getPath());
					}
					writableObject.flushBuffer();
					long dataSize = writableObject.getDataSize();
					long starttime = 0;
					long endtime = 0;
					long timeElapsed = 0;
					long objectSize = 0;
					if (dataSize > 0) {
						starttime = System.currentTimeMillis();
					}
					// close object
					writableObject.close();
					if (dataSize > 0) {
						endtime = System.currentTimeMillis();
						objectSize = fParent.getObjectStorageClient().getObjectSize(osObject.getPath());
						timeElapsed = endtime - starttime;
						if (!fParent.isMultipartUpload()) {
							// ensure the elapsed time is greater 0 , to avoid division by zero problems later. 
							if (timeElapsed == 0) {
								timeElapsed = 1;
								if (TRACE.isLoggable(TraceLevel.TRACE)) {
									TRACE.log(TraceLevel.TRACE, "increasing the elapsed time from 0 to 1 milliseconds. This may slightly distort metrics");
								}
							}
		
							if (TRACE.isLoggable(TraceLevel.INFO)) {
								TRACE.log(TraceLevel.INFO, "uploaded: "+ osObject.getPath() + ", size: " + objectSize + " Bytes, duration: "+timeElapsed + "ms, Data sent/sec: "+(objectSize/timeElapsed)+" KB"+ ", data processed: " + dataSize + " Bytes in "+timeElapsed+" ms");
							}
							fParent.updateUploadSpeedMetrics(objectSize, (objectSize/timeElapsed), timeElapsed);
						}
						else {
							if (TRACE.isLoggable(TraceLevel.INFO)) {
								TRACE.log(TraceLevel.INFO, "uploaded: "+ osObject.getPath() + ", size: " + objectSize + " Bytes" + ", data processed: " + dataSize + " Bytes, closeTime: "+timeElapsed+" ms");
							}
							// if multipart upload, then we don't know the start time of upload and can not estimate the upload rate, but close time metric is filled with timeElapsed
							fParent.updateUploadSpeedMetrics(objectSize, 0, timeElapsed);
						}				
					}
					// update metrics
					fParent.getActiveObjectsMetric().incrementValue(-1);
					fParent.getCloseObjectsMetric().increment();
					fParent.updateCachedDataMetrics(dataSize, false);
		
					// submit output 
					fParent.submitOnOutputPort(osObject.getPath(), objectSize);
				}
				else {
					// update metrics
					fParent.getActiveObjectsMetric().incrementValue(-1);					
				}				
			}
		} 
		catch (Exception e) {
			// for more detailed error analysis - implement logic for AmazonS3Exception analysis
			// the key parameters that should be taken into account are: error code and status code			
			String errRootCause = com.ibm.streamsx.objectstorage.Utils.getErrorRootCause(e);
			String errMsg = Messages.getString("OBJECTSTORAGE_SINK_OBJECT_CLOSE_FAILURE", fParent.getBucketName(), osObject.getPath(), errRootCause);
			if (TRACE.isLoggable(TraceLevel.ERROR)) {
				TRACE.log(TraceLevel.ERROR,	errMsg);
				TRACE.log(TraceLevel.ERROR,	"Failed to write object '" + osObject.getPath() + "' to bucket '"
						 + fParent.getBucketName() + "'. Exception: " + e.getMessage());
			}
	    	LOGGER.log(TraceLevel.ERROR, errMsg);
	    	
	    	// decrement active objects metric even 
 			// when the close failed
	    	fParent.getActiveObjectsMetric().incrementValue(-1);
	    			
			throw new RuntimeException(e);
		} 
		
	}
	
}
