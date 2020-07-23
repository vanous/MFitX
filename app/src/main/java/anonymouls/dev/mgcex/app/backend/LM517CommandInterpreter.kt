package anonymouls.dev.mgcex.app.backend

import android.os.Handler
import anonymouls.dev.mgcex.app.backend.ApplicationStarter.Companion.commandHandler

import anonymouls.dev.mgcex.databaseProvider.SleepRecordsTable
import anonymouls.dev.mgcex.util.PreferenceListener
import anonymouls.dev.mgcex.util.Utils
import java.nio.ByteBuffer
import java.util.*


@ExperimentalStdlibApi
@ExperimentalUnsignedTypes
class LM517CommandInterpreter : CommandInterpreter() {
    companion object {
        const val CodeName = "LM716"

        private const val syncTimeCommandHeader = "CD00091201010004"
        private const val longAlarmHeader = "CD0014120111000F"

        private const val UARTServiceUUIDString = "6e400001-b5a3-f393-e0a9-e50e24dcca9d"
        private const val UARTRXUUIDString = "6e400002-b5a3-f393-e0a9-e50e24dcca9d"
        private const val UARTTXUUIDString = "6e400003-b5a3-f393-e0a9-e50e24dcca9d"
        private const val UARTDescriptor = "00002902-0000-1000-8000-00805f9b34fb" // 00002902-0000-1000-8000-00805f9b34fb
        private const val PowerServiceString = "0000180f-0000-1000-8000-00805f9b34fb"
        private const val PowerTXString = "00002a19-0000-1000-8000-00805f9b34fb"
        private const val PowerTX2String = "00002a19-0000-0000-8000-00805f9b34fb"
        private const val PowerDescriptor = "00002902-0000-1000-8000-00805f9b34fb"
    }

    init {
        UartService.RX_SERVICE_UUID = UUID.fromString(UARTServiceUUIDString)
        UartService.RX_CHAR_UUID = UUID.fromString(UARTRXUUIDString)
        UartService.TX_CHAR_UUID = UUID.fromString(UARTTXUUIDString)
        UartService.TXServiceDesctiptor = UUID.fromString(UARTDescriptor)
        UartService.PowerServiceUUID = UUID.fromString(PowerServiceString)
        UartService.PowerTXUUID = UUID.fromString(PowerTXString)
        UartService.PowerDescriptor = UUID.fromString(PowerDescriptor)
        this.hRRealTimeControlSupport = false
        this.vibrationSupport = true
        this.stepsTargetSettingSupport = true
        this.sittingReminderSupport = true
    }

    //region Helpers

    private fun createSpecialCalendar(isHR: Boolean = false): Calendar {
        val result = Calendar.getInstance()
        result.set(Calendar.MONTH, 10)
        if (!isHR) result.set(Calendar.DAY_OF_MONTH, 5) else result.set(Calendar.DAY_OF_MONTH, 6)
        // TODO wtf? now is always 6, previously was 8, for night -3
        result.set(Calendar.YEAR, 1991)
        result.set(Calendar.HOUR_OF_DAY, 0)
        result.set(Calendar.MINUTE, 0)
        result.set(Calendar.SECOND, 0)
        return result
    }

    private fun decryptDays(offset: Short, targetCalendar: Calendar?, isHR: Boolean = false): Calendar {
        val result = targetCalendar ?: createSpecialCalendar(isHR)
        result.add(Calendar.DAY_OF_YEAR, offset.toInt())
        return result
    }

    private fun decryptTime(offset: Int, targetCalendar: Calendar): Calendar {
        targetCalendar.set(Calendar.HOUR_OF_DAY, 0)
        targetCalendar.set(Calendar.MINUTE, 0)
        targetCalendar.set(Calendar.SECOND, 0)
        targetCalendar.add(Calendar.SECOND, offset)
        return targetCalendar
    }

    //endregion

    //region Proceeders

    private fun hrRecordProceeder(Input: ByteArray) {
        // WARNING. Shit like pressure and ox% is ignoring
        if (Input.size != 20) return
        var buffer = ByteBuffer.wrap(Input, 8, 2)
        var recordTime = decryptDays(buffer.short, null, true)
        buffer = ByteBuffer.wrap(Input, 13, 4)
        recordTime = decryptTime(byteArrayToInt(buffer.array(), 13, 3), recordTime)
        val hrValue = Input[Input.size - 1]
        callback?.hrHistoryRecord(recordTime, hrValue.toInt())
    }

    private fun mainRecordProceeder(Input: ByteArray) {
        if (Input.size != 20) return
        var buffer = ByteBuffer.wrap(Input, 8, 2)
        val recordTime = decryptDays(buffer.short, null, true)
        recordTime.set(Calendar.HOUR_OF_DAY, Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
        recordTime.set(Calendar.MINUTE, Calendar.getInstance().get(Calendar.MINUTE))
        recordTime.set(Calendar.SECOND, Calendar.getInstance().get(Calendar.SECOND))

        buffer = ByteBuffer.wrap(Input, 12, 2)
        val steps = buffer.short.toInt()

        buffer = ByteBuffer.wrap(Input, 18, 2)
        val calories = buffer.short.toInt()
        this.callback?.mainHistoryRecord(recordTime, steps, calories)
    }

    private fun stepsSettingProceeder(Input: ByteArray) {
        if (Input.size != 11 && Input[3].toUByte() != (26).toUByte()) return
        val buffer = ByteBuffer.wrap(Input, Input.size - 2, 2)
        val steps = buffer.short
        Algorithm.SelfPointer?.baseContext?.let {
            Utils.getSharedPrefs(it).edit()
                    .putString(PreferenceListener.Companion.PrefsConsts.targetSteps, steps.toString()).apply()
        }
    }

    private fun sleepDataProceeder(Input: ByteArray) {
        if (Input[2].toUByte() != (13).toUByte() || Input.size != 16) return

        var buffer = ByteBuffer.wrap(Input, 8, 2)
        val recordTime = decryptDays(buffer.short, null)
        buffer = ByteBuffer.wrap(Input, 12, 2)
        recordTime.set(Calendar.HOUR_OF_DAY, 22); recordTime.set(Calendar.MINUTE, 0) // Sleep Tracking starts here
        recordTime.add(Calendar.MINUTE, buffer.short.toInt())
        val castedCode = when (Input[Input.size - 1].toUByte()) {
            (2).toUByte() -> SleepRecordsTable.SleepRecord.RecordTypes.Awake.code
            (1).toUByte() -> SleepRecordsTable.SleepRecord.RecordTypes.Deep.code
            (3).toUByte() -> SleepRecordsTable.SleepRecord.RecordTypes.LightOrOverall.code
            else -> -1
        }
        this.callback?.sleepHistoryRecord(recordTime, 8, castedCode)
        // this shit calculaes every 8 minutes. Kostil or no?
    }

    private fun commandsEntryPoint(Input: ByteArray) {
        if (Input[0].toUByte() != 205.toUByte()) return
        if (Input[1].toUByte() != 0.toUByte()) return
        //if (Input[2].toUByte() != 21.toUByte()) return
        when (Input[5].toUByte()) {
            (3).toUByte() -> sleepDataProceeder(Input)
            (2).toUByte() -> stepsSettingProceeder(Input)
            (14).toUByte() -> hrRecordProceeder(Input)
            (12).toUByte() -> mainRecordProceeder(Input)
        }
        // TODO
    }

    //endregion

    override fun syncTime(SyncTime: Calendar?) {
        val targetTime = SyncTime ?: Calendar.getInstance()
        var mainNumber = (targetTime.get(Calendar.YEAR) - 2000) shl 26
        mainNumber = mainNumber or (targetTime.get(Calendar.MONTH) + 1 shl 22)
        var dayOfMonth = targetTime.get(Calendar.DAY_OF_MONTH) * 2
        var time = targetTime.get(Calendar.HOUR_OF_DAY)
        if (time >= 16) {
            dayOfMonth += 1
            time -= 16
        }
        mainNumber = mainNumber or (dayOfMonth shl 16)
        mainNumber = mainNumber or (time shl 12)
        mainNumber = mainNumber or (targetTime.get(Calendar.MINUTE) shl 6)
        mainNumber = mainNumber or (targetTime.get(Calendar.SECOND))
        postCommand(hexStringToByteArray(syncTimeCommandHeader + Integer.toHexString(mainNumber)))
    }

    override fun eraseDatabase() {
        postCommand(hexStringToByteArray("CD0006150106000101"))
    }

    override fun restoreToDefaults() {
        postCommand(hexStringToByteArray("CD00021D01"))
    }

    override fun stopLongAlarm() {
        // TODO: TEST
        postCommand(hexStringToByteArray(longAlarmHeader + "000000000000"))
    }

    override fun setGyroAction(IsEnabled: Boolean) {
        var request = "CD:00:0A:12:01:09:00:05"
        request += if (IsEnabled) "0101" else "0001"
        request += Utils.subIntegerConversionCheck(Integer.toHexString(1048580))
        postCommand(hexStringToByteArray(request))
    }

    override fun buildLongNotify(Message: String) {
        val request = hexStringToByteArray(longAlarmHeader + "0100")
        val arr = hexStringToByteArray(messageToHexValue(Message, 13).replace("00", "FF"))
        var req1 = arr.copyOfRange(0, 10)
        req1 = request.plus(req1)
        postCommand(req1)
        req1 = arr.copyOfRange(10, 13)
        Handler(commandHandler.looper).postDelayed({ postCommand(req1) }, 1000)
    }

    override fun commandAction(Input: ByteArray, characteristic: UUID) {
        when (characteristic) {
            UUID.fromString(UARTTXUUIDString) -> {
                commandsEntryPoint(Input)
            }
            UUID.fromString(PowerTX2String), UUID.fromString(PowerTXString) -> {
                this.callback?.batteryInfo(Input[Input.size - 1].toInt())
            }
        }
        //TODO("Not yet implemented")
    }

    override fun getMainInfoRequest() {
        postCommand(hexStringToByteArray("cd:00:06:12:01:15:00:01:01"))
        Handler(commandHandler.looper)
                .postDelayed({ postCommand(hexStringToByteArray("cd:00:06:15:01:06:00:01:01")) }, 1300)
    }

    override fun requestSleepHistory(FromDate: Calendar) {
        // nice device in monitoring mode it is suicide to request data
        // LM715 have monitor period from 10PM to 9AM
        if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) in 10..21)
            postCommand(hexStringToByteArray("cd:00:06:15:01:0d:00:01:01"))
    }

    override fun requestHRHistory(FromDate: Calendar?) {
        postCommand(hexStringToByteArray("CD:00:06:15:01:06:00:01:01"))
        // Warning! This device will send data ONCE and delete this record from device.
    }

    override fun hRRealTimeControl(Enable: Boolean) {
        return // NOT SUPPORTED
    }

    override fun setAlarm(AlarmID: Long, IsEnabled: Boolean, Hour: Int, Minute: Int, Days: Int) {
        //TODO("Not yet implemented")
    }

    override fun fireNotification(Input: String) {
        synchronized(LM517CommandInterpreter::class) {
            var arr = hexStringToByteArray(messageToHexValue(Input, 165, false).replace("00", "FF"))
            var request = "CD00" + Utils.subIntegerConversionCheck(Integer.toHexString(arr.size + 8)) + "12011200"
            var offset = 10
            var Req1 = if (offset > arr.size) {
                request += Utils.subIntegerConversionCheck(Integer.toHexString(Input.length + 2)) + "010000"
                hexStringToByteArray(request).plus(arr.copyOfRange(0, arr.size))
            } else {
                request += Utils.subIntegerConversionCheck(Integer.toHexString(Input.length + 3)) + "010000"
                offset = 9; hexStringToByteArray(request).plus(arr.copyOfRange(0, 9))
            }
            postCommand(Req1)
            while (offset < arr.size) {
                if (arr.size > offset + 20)
                    Req1 = arr.copyOfRange(offset, offset + 20)
                else
                    Req1 = arr.copyOfRange(offset, arr.size)
                postCommand(Req1)
                offset += 20
            }
        }
    }

    override fun requestBatteryStatus() {
        Algorithm.SelfPointer?.uartService?.readCharacteristic(
                UartService.PowerServiceUUID, UartService.PowerTXUUID)
    }

    override fun requestManualHRMeasure(cancel: Boolean) {
        synchronized(LM517CommandInterpreter::class) {
            var request = "CD00061201180001"
            request += if (cancel) "00" else "01"
            postCommand(hexStringToByteArray(request))
        }
    }

    override fun setVibrationSetting(enabled: Boolean) {
        synchronized(LM517CommandInterpreter::class) {
            var request = "CD 00 09 12 01 08 04"
            request += if (enabled) "01" else "00"
            request += "00 00 00"
            postCommand(hexStringToByteArray(request))
        }
    }

    override fun setTargetSteps(count: Int) {
        if (count < 500) return
        synchronized(LM517CommandInterpreter::class) {
            postCommand(hexStringToByteArray("CD:00:09:12:01:03:00:04:00:00" + Integer.toHexString(count)))
        }
    }

    override fun setSittingReminder(enabled: Boolean) {
        var request = "CD:00:0D:12:01:05:00:08:00"
        request += if (enabled) "01" else "00"
        synchronized(LM517CommandInterpreter::class) {
            postCommand(hexStringToByteArray(request + "00:96:04:08:16:00"))
        }
    }

    override fun requestSettings() {
        synchronized(LM517CommandInterpreter::class) {
            postCommand(hexStringToByteArray("CD:00:05:1A:01:02:00:00"))
        }
    }
}