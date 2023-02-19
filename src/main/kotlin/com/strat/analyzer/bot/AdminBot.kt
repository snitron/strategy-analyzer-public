package com.strat.analyzer.bot

import com.strat.analyzer.utils.Constants
import com.strat.analyzer.utils.startSuspended
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.telegram.telegrambots.bots.TelegramLongPollingBot
import org.telegram.telegrambots.meta.api.objects.Update
import javax.annotation.PostConstruct

@Component
class AdminBot(
    @Value("\${telegram.AdminBot.botName}")
    private val botName: String,
    @Value("\${telegram.AdminBot.token}")
    private val token: String,
    private val constants: Constants
) : TelegramLongPollingBot(), Bot {

    @PostConstruct
    fun iinit() {
        constants.adminMessageSender = this
    }

    override fun getBotToken() = token

    override fun getBotUsername() = botName

    override fun onUpdateReceived(update: Update) {
        if (update.hasMessage() && update.message.hasText()) {
            val adminName =
                if (update.hasCallbackQuery()) update.callbackQuery.from.userName else update.message.from.userName
            val adminChatId =
                if (update.hasCallbackQuery()) update.callbackQuery.message.chatId else update.message.chatId.toLong()
            
            if (adminName in listOf("")) {
                startSuspended { processAdminUpdate(update, adminChatId) }
            }
        }
    }

    suspend fun processAdminUpdate(update: Update, chatId: Long) {
        val splitted = update.message.text.split(" ")

        when (splitted[0].lowercase().split("@")[0]) {
            "/start" -> {
                sendMessage("Привет!!!!", chatId)
            }

            "/getbalance" -> {
                sendMessage("${constants.balanceGetter?.getBalance()} USDT", chatId)
            }

            "/getactiveorders" -> {
                for (deal in constants.deals) {
                    sendMessage(deal.toString().replace(",", "\n"), chatId)
                }
            }

            "/enable" -> {
                when (splitted[1].lowercase()) {
                    "long", "buy", "bullish" -> {constants.longEnabled = true}
                    "short", "sell", "bearish" -> {constants.shortEnabled = true}
                    else -> {
                        sendMessage("Некорректный параметр", chatId = chatId)
                        return
                    }
                }

                sendMessage("Направление активировано", chatId = chatId)
            }

            "/setamount" -> {
                constants.dealAmount = splitted[1].toBigDecimal()

                sendMessage("Размер сделки установлен", chatId = chatId)
            }

            "/setstoppercent" -> {
                constants.stopPercent = splitted[1].toBigDecimal()

                sendMessage("Размер стопа установлен", chatId = chatId)
            }

            "/setstopbupercent" -> {
                constants.stopBUPercent = splitted[1].toBigDecimal()

                sendMessage("Размер стопа БУ установлен", chatId = chatId)
            }

            "/setrsilength" -> {
                constants.rsiLength = splitted[1].toInt()

                sendMessage("Длина RSI установлена", chatId = chatId)
            }

            "/settimeframe" -> {
                constants.timeFrame = splitted[1].toLong()

                sendMessage("Таймфрейм установлен", chatId = chatId)
            }

            "/settakes" -> {
                constants.takes = splitted.subList(1, splitted.size).map { it.toBigDecimal() }

                sendMessage("Тейки установлены", chatId = chatId)
            }

            "/setrsilevels" -> {
                constants.rsiLevels = splitted.subList(1, splitted.size).map { it.toDouble() }

                sendMessage("Уровни установлены", chatId = chatId)
            }

            "/setdivlevels" -> {
                constants.diverLevels = splitted.subList(1, splitted.size).map { it.toInt() }

                sendMessage("Границы дивергенции установлены", chatId = chatId)
            }
            "/settimesleeps" -> {
                constants.timeSleeps = splitted.subList(1, splitted.size).map { it.toBigDecimal() }

                sendMessage("Время ожидания установлено", chatId = chatId)
            }

            "/setrsiepsilon" -> {
                constants.rsiEpsilon = splitted[1].toDouble()

                sendMessage("Погрешность RSI установлена", chatId = chatId)
            }

            "/setleverage" -> {
                constants.leverage = splitted[1].toBigDecimal()

                sendMessage("Плечо установлено", chatId = chatId)
            }

            "/disable" -> {
                when (splitted[1].lowercase()) {
                    "long", "buy", "bullish" -> { constants.longEnabled = false }
                    "short", "sell", "bearish" -> { constants.shortEnabled = false }
                    else -> {
                        sendMessage("Некорректный параметр", chatId = chatId)
                        return
                    }
                }

                sendMessage("Направление деактивировано", chatId = chatId)
            }

            "/status" -> sendMessage("Настройки бота:\n" +
                    "Long enabled: ${constants.longEnabled}\n" +
                    "Short enabled: ${constants.shortEnabled}\n" +
                    "Order amount: ${constants.dealAmount}\n" +
                    "Stop percent: ${constants.stopPercent}\n" +
                    "Stop BU percent: ${constants.stopBUPercent}\n" +
                    "RSI length: ${constants.rsiLength}\n" +
                    "Takes: ${constants.takes}\n" +
                    "RSI levels: ${constants.rsiLevels}\n" +
                    "Last update: ${constants.lastUpdate}\n" +
                    "Timeframe: ${constants.timeFrame}m\n" +
                    "RSI EPS: ${constants.rsiEpsilon}\n" +
                    "Time sleeps: ${constants.timeSleeps}\n" +
                    "Coroutines count: ${constants.coroutinesCount.get()}\n"+
                    "Leverage: ${constants.leverage}\n\n"+
                    "Настройки индюка:\n"+
                    "Divergence levels: ${constants.diverLevels}\n"
                , chatId = chatId)

            "/lasttimes" -> sendMessage(text = constants.candlesLastTime.toString(), chatId = chatId)

            "/laststarttimes" -> sendMessage(text = constants.candlesStartTime.toString(), chatId = chatId)

            "/help" -> {
                sendMessage(
                    """
                    Настройки бота:
                    /getbalance --- информация о балансах пользователей 
                    /getactiveorders --- информация об открытых сделках
                    /enable *long|short* --- активировать направление
                    /disable *long|short* --- деактивировать направление
                    /setamount --- установить размер сделки
                    /setstoppercent --- установить процент стопа
                    /setstopbupercent --- установить процент стопа БУ
                    /setrsilength --- установить длину RSI
                    /settakes --- установить тейки
                    /status --- информация о настройках бота
                    /setrsilevels --- установить уровни RSI
                    /setrsiepsilon --- установить погрешность RSI
                    /settimeframe --- установить таймфрейм
                    /lasttimes --- посмотреть последние времена прихода свечек
                    /laststarttimes --- посмотреть стартовые времена свечек
                    /settimesleeps --- установить время ожидания для каждого тф
                    /setleverage --- установить плечо
                    
                    Настройки индюка:
                    /setdivlevels --- установить границы дивергенции
                  
                    """.trimIndent(), chatId
                )
            }
        }
    }

}
