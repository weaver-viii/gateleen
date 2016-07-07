package org.swisspush.gateleen.queue.queuing.circuitbreaker.lua;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

/**
 * @author https://github.com/mcweba [Marc-Andre Weber]
 */
public class UpdateQueueCircuitBreakerStatsLuaScriptTests extends AbstractLuaScriptTest {

    private final String circuitInfoKey = "q:infos";
    private final String circuitSuccessKey = "q:success";
    private final String circuitFailureKey = "q:failure";

    @Test
    public void testCalculateErrorPercentage(){
        assertThat(jedis.exists(circuitInfoKey), is(false));
        assertThat(jedis.exists(circuitSuccessKey), is(false));
        assertThat(jedis.exists(circuitFailureKey), is(false));

        String update_success = "q:success";
        String update_fail = "q:failure";

        // adding 3 failing requests
        evalScriptUpdateQueueCircuitBreakerStats(update_fail, "req_1", "url_pattern", 0, 50, 10, 4, 10);
        evalScriptUpdateQueueCircuitBreakerStats(update_fail, "req_2", "url_pattern", 1, 50, 10, 4, 10);
        evalScriptUpdateQueueCircuitBreakerStats(update_fail, "req_3", "url_pattern", 2, 50, 10, 4, 10);

        // asserts
        assertThat(jedis.exists(circuitInfoKey), is(true));
        assertThat(jedis.exists(circuitSuccessKey), is(false));
        assertThat(jedis.exists(circuitFailureKey), is(true));
        assertStateAndErroPercentage("closed", 100); // state should still be 'closed' because the minSampleThreshold (4) is not yet reached

        // add 1 successful request => now the minSampleThreshold is reached
        evalScriptUpdateQueueCircuitBreakerStats(update_success, "req_4", "url_pattern", 3, 50, 10, 4, 10);

        assertThat(jedis.exists(circuitInfoKey), is(true));
        assertThat(jedis.exists(circuitSuccessKey), is(true));
        assertThat(jedis.exists(circuitFailureKey), is(true));
        assertStateAndErroPercentage("open", 75);

        // add 2 more successful requests => failurePercentage should drop
        evalScriptUpdateQueueCircuitBreakerStats(update_success, "req_5", "url_pattern", 4, 50, 10, 4, 10);
        evalScriptUpdateQueueCircuitBreakerStats(update_success, "req_6", "url_pattern", 5, 50, 10, 4, 10);
        assertStateAndErroPercentage("open", 50);

        // add 1 more successful request => failurePercentage should drop and state should switch to 'half_open'
        evalScriptUpdateQueueCircuitBreakerStats(update_success, "req_7", "url_pattern", 6, 50, 10, 4, 10);
        assertStateAndErroPercentage("open", 42);

        // add 1 more failing request => failurePercentage should raise again but state should remain 'half_open'
        evalScriptUpdateQueueCircuitBreakerStats(update_fail, "req_8", "url_pattern", 7, 50, 10, 4, 10);
        assertStateAndErroPercentage("open", 50);

        // add 3 more failing request => failurePercentage should raise again but state should remain 'half_open'
        evalScriptUpdateQueueCircuitBreakerStats(update_fail, "req_9", "url_pattern", 8, 50, 10, 4, 10);
        evalScriptUpdateQueueCircuitBreakerStats(update_fail, "req_10", "url_pattern", 9, 50, 10, 4, 10);
        evalScriptUpdateQueueCircuitBreakerStats(update_fail, "req_11", "url_pattern", 10, 50, 10, 4, 10);
        assertStateAndErroPercentage("open", 63);

        assertPattern("url_pattern");
    }


    @Test
    public void testDontExceedMaxSetSize(){
        long maxSetSize = 10;

        assertSizeSizeNotExceedingLimit(circuitSuccessKey, maxSetSize);
        for (int i = 1; i <= 20; i++) {
            evalScriptUpdateQueueCircuitBreakerStats("q:success", "req_"+i, "url_pattern", i, 50, 10, 4, maxSetSize);
        }
        assertSizeSizeNotExceedingLimit(circuitSuccessKey, maxSetSize);

        // assert that the 'oldest' entries have been removed
        Set<String> remainingSetEntries = jedis.zrangeByScore(circuitSuccessKey, Long.MIN_VALUE, Long.MAX_VALUE);
        assertThat(remainingSetEntries.size(), equalTo(10));
        for(int i = 1; i <= 10; i++){
            assertThat(remainingSetEntries.contains("req_"+i), is(false));
        }
        for(int i = 11; i <= 20; i++){
            assertThat(remainingSetEntries.contains("req_"+i), is(true));
        }

        assertPattern("url_pattern");
    }

    @Test
    public void testOnlyRespectEntriesWithinAgeRange(){
        String update_success = "q:success";
        String update_fail = "q:failure";
        evalScriptUpdateQueueCircuitBreakerStats(update_success, "req_1", "url_pattern", 1, 50, 3, 1, 100);
        assertStateAndErroPercentage("closed", 0);
        evalScriptUpdateQueueCircuitBreakerStats(update_success, "req_2", "url_pattern", 2, 50, 3, 1, 100);
        assertStateAndErroPercentage("closed", 0);
        evalScriptUpdateQueueCircuitBreakerStats(update_fail, "req_3", "url_pattern", 3, 50, 3, 1, 100);
        assertStateAndErroPercentage("closed", 33);
        evalScriptUpdateQueueCircuitBreakerStats(update_fail, "req_4", "url_pattern", 4, 50, 3, 1, 100);
        assertStateAndErroPercentage("open", 50);
        evalScriptUpdateQueueCircuitBreakerStats(update_fail, "req_5", "url_pattern", 5, 50, 3, 1, 100);
        assertStateAndErroPercentage("open", 75); // req_1 is out of range by now
        evalScriptUpdateQueueCircuitBreakerStats(update_fail, "req_6", "url_pattern", 6, 50, 3, 1, 100);
        assertStateAndErroPercentage("open", 100); // req_2 is out of range by now
        evalScriptUpdateQueueCircuitBreakerStats(update_success, "req_7", "url_pattern", 7, 50, 3, 1, 100);
        assertStateAndErroPercentage("open", 75); // req_3 is out of range by now
        evalScriptUpdateQueueCircuitBreakerStats(update_success, "req_8", "url_pattern", 8, 50, 3, 1, 100);
        assertStateAndErroPercentage("open", 50); // req_4 is out of range by now
        evalScriptUpdateQueueCircuitBreakerStats(update_success, "req_9", "url_pattern", 9, 50, 3, 1, 100);
        assertStateAndErroPercentage("open", 25); // req_5 is out of range by now
        evalScriptUpdateQueueCircuitBreakerStats(update_success, "req_10", "url_pattern", 10, 50, 3, 1, 100);
        assertStateAndErroPercentage("open", 0); // req_6 is out of range by now

        assertPattern("url_pattern");
    }

    private void assertStateAndErroPercentage(String state, int percentage){
        assertThat(jedis.hget(circuitInfoKey, "state"), equalTo(state));
        String percentageAsString = jedis.hget(circuitInfoKey, "failRatio");
        assertThat(Integer.valueOf(percentageAsString), equalTo(percentage));
    }

    private void assertPattern(String pattern){
        assertThat(jedis.hget(circuitInfoKey, "pattern"), equalTo(pattern));
    }

    private void assertSizeSizeNotExceedingLimit(String setKey, long maxSetSize){
        assertThat(jedis.zcard(setKey) <= maxSetSize, is(true));
    }

    private Object evalScriptUpdateQueueCircuitBreakerStats(String circuitKeyToUpdate, String uniqueRequestID,
                                                            String pattern, long timestamp, int errorThresholdPercentage,
                                                            long entriesMaxAgeMS, long minSampleCount, long maxSampleCount) {

        String script = readScript(QueueCircuitBreakerLuaScripts.OPEN_CIRCUIT.getFilename());
        List<String> keys = Arrays.asList(
                circuitInfoKey,
                circuitSuccessKey,
                circuitFailureKey,
                circuitKeyToUpdate
        );

        List<String> arguments = Arrays.asList(
                uniqueRequestID,
                pattern,
                String.valueOf(timestamp),
                String.valueOf(errorThresholdPercentage),
                String.valueOf(entriesMaxAgeMS),
                String.valueOf(minSampleCount),
                String.valueOf(maxSampleCount)
        );
        return jedis.eval(script, keys, arguments);
    }
}
