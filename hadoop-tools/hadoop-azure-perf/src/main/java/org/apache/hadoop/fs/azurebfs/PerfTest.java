package org.apache.hadoop.fs.azurebfs;

import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.azurebfs.constants.FSOperationType;
import org.apache.hadoop.fs.azurebfs.services.AbfsInputStream;
import org.apache.hadoop.fs.statistics.IOStatisticsLogging;
import org.apache.hadoop.fs.statistics.IOStatisticsSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.Random;

import static org.apache.hadoop.fs.CommonConfigurationKeys.IOSTATISTICS_LOGGING_LEVEL_INFO;
import static org.apache.hadoop.fs.azurebfs.constants.FileSystemConfigurations.ONE_KB;
import static org.apache.hadoop.fs.azurebfs.constants.FileSystemConfigurations.ONE_MB;

public class PerfTest extends PerfTestBase {

    Long TEST_TIME = 60*60*1000l;
    private final String TEST_PATH = "/testfile";

    private final Long TEN_MINUTE = 10 * 60 * 1000l;

    Logger LOG =
            LoggerFactory.getLogger(PerfTest.class);

    public PerfTest() throws Exception {
        super();
        this.setup();
    }

    public static void main(String[] args) throws Exception {
        PerfTest perfTest = new PerfTest();
        perfTest.perfTest(args[0], Integer.parseInt(args[1]), args[2], Integer.parseInt(args[3]), args[4],
                Integer.parseInt(args[5]), args[6]);
    }

    private static Integer getMultiplier(String metric) {
        if("MB".equalsIgnoreCase(metric)) {
            return ONE_MB;
        }
        if("KB".equalsIgnoreCase(metric)) {
            return ONE_KB;
        }
        return 1;
    }
    public void perfTest(String filePath, Integer fileSizeUnit, String fileSizeMetric, Integer seek1Unit, String seek1Metric,
                         Integer seek2Unit, String seek2Metric) throws Exception {
        MetricHelper.startPlot(filePath);
        final Integer fileSize = fileSizeUnit * getMultiplier(fileSizeMetric);
        final Integer seek1 = seek1Unit * getMultiplier(seek1Metric);
        final Integer seek2 = seek2Unit * getMultiplier(seek2Metric);

        final Long start = new Date().toInstant().toEpochMilli();

        for(int i=0; i<5;i++) {
            new Thread(() -> {
                while(true) {
                    try {
                        if(new Date().toInstant().toEpochMilli() > (start + TEST_TIME)) {
                            break;
                        }
                        testReadWriteAndSeek(fileSize, 4* ONE_MB, seek1, seek2);
                    } catch (Exception e) {

                    }
                }
            }).start();
        }

        while(true);

    }

    private void testReadWriteAndSeek(int fileSize, int bufferSize, Integer seek1, Integer seek2) throws Exception {
        final AzureBlobFileSystem fs = getFileSystem();
        final AbfsConfiguration abfsConfiguration = fs.getAbfsStore().getAbfsConfiguration();
        abfsConfiguration.setWriteBufferSize(bufferSize);
        abfsConfiguration.setReadBufferSize(bufferSize);

        final byte[] b = new byte[fileSize];
        new Random().nextBytes(b);

        Path testPath = path(TEST_PATH);
        FSDataOutputStream stream = fs.create(testPath);
        try {
            stream.write(b);
        } finally{
            stream.close();
        }
        IOStatisticsLogging.logIOStatisticsAtLevel(LOG, IOSTATISTICS_LOGGING_LEVEL_INFO, stream);

        final byte[] readBuffer = new byte[fileSize];
        IOStatisticsSource statisticsSource = null;
        try (FSDataInputStream inputStream = fs.open(testPath)) {
            statisticsSource = inputStream;
            ((AbfsInputStream) inputStream.getWrappedStream()).registerListener(
                    new TracingHeaderValidator(abfsConfiguration.getClientCorrelationId(),
                            fs.getFileSystemId(), FSOperationType.READ, true, 0,
                            ((AbfsInputStream) inputStream.getWrappedStream())
                                    .getStreamID()));
            Long start = new Date().toInstant().toEpochMilli();
            inputStream.seek(fileSize - seek1);
            inputStream.read(readBuffer, 0, seek1);

            if(seek2 != 0) {
                inputStream.seek(fileSize - seek1 - seek2);
                inputStream.read(readBuffer, 0, seek2);
            }

            MetricHelper.push(new Date().toInstant().toEpochMilli() - start);
        }
        IOStatisticsLogging.logIOStatisticsAtLevel(LOG, IOSTATISTICS_LOGGING_LEVEL_INFO, statisticsSource);
    }
}
