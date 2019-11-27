package app.jibiki.model

class Snowflake(
        val id: Long? = null
) {
    override fun toString(): String {
        return id.toString()
    }

    companion object {
        private const val dataCenterIdBits = 5
        private const val workerIdBits = 5
        private const val sequenceBits = 12

        private const val epoch = 1546300800000L
        private const val dataCenterId = 0L
        private const val workerId = 0L
        private const val maxSequence = (-1L).xor((-1L).shl(sequenceBits))
        private var sequence = 0L
        private var lastTimestamp = -1L

        private const val timestampShift = sequenceBits + dataCenterIdBits + workerIdBits
        private const val dataCenterIdShift = sequenceBits + workerIdBits
        private const val workerIdShift = sequenceBits

        @Synchronized
        fun next(): Snowflake {
            val currentTimestamp = System.currentTimeMillis()

            if (currentTimestamp < lastTimestamp)
                throw IllegalStateException("Time moved backwards!")

            sequence = if (currentTimestamp == lastTimestamp) {
                (sequence + 1).and(maxSequence)
            } else {
                0L
            }

            lastTimestamp = currentTimestamp

            return Snowflake(
                    (currentTimestamp - epoch).shl(timestampShift)
                            .or(dataCenterId.shl(dataCenterIdShift))
                            .or(workerId.shl(workerIdShift))
                            .or(sequence)
            )
        }
    }
}