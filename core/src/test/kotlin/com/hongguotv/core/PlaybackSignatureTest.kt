package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

/** Fixed wire vectors published with openhongguo PR 7 (Apache-2.0). */
class PlaybackSignatureTest {
    @Test fun ladonMatchesPublishedVectors() {
        assertEquals("AQIDBKM7Y7bWnOIUXmTybD6/MpGbuge4sBwUJsyHVivhtMsH",
            PlaybackSignature.ladon(1700000000,0x04030201,"1967"))
        assertEquals("AQIDBPhgjvKhYEWp2lodHDSBnITF8APHUjBLe2ja98bNrRn+",
            PlaybackSignature.ladon(1800000000,0x04030201,"8662"))
    }
    @Test fun argusMatchesPublishedVectors() {
        assertEquals("8oGwZRLe2V38m+1gfqIeLa2MoV9ZBWosW5Y0NXKHAB2aEYRF86eNA7rLWm6xUCiwYMBDI/yq5ajsCmDF+Zws9KOfZO0mqezSYjF9/D5C1EliRxXJBBuZBaQZJgT6gakwiRnJkm+8F1ZVFIFO9GxkdwLxdxYidY2sLQjAsG8q4241ayJ/0zOxsVJNlqzPs7PfJb83zY6J1eIUaugTB6QnbXOKEQUgLhiYC0Uszr1vBP2Vj1cgtWu0IgouGM/ie3CohEAkY2oOkN/W1leWheBszijv",
            PlaybackSignature.argus("aid=1967&device_id=test-device&iid=test-install",null,1700000000,659662259))
        assertEquals("8oHyrVYEy1xDCDlx64ko40qC6JC9VYY/ZkjJzEl50iSFDw8Yfph1hupLxfKrA9GswSEz0Yi4goA7OQ4u+H8ECdOaHRbHCQV+QFNgNz4TTRpioctLgrN97GbIKIQgZJjNRNiwbGqa+jIbLonncbTbsGIMv2WphwNMhXazKSewxik7/ecf0/SUOIiVd/3LhLMGeCIy+/ngOQr9VXnvmCb8HyzUbr01+pgwc1OKSfU29+FP4vYSq2WdmLCpvopUNQZhB8U=",
            PlaybackSignature.argus("",null,1700000000,0))
    }
}
