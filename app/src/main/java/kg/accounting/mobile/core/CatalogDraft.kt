package kg.accounting.mobile.core

data class Selection(
    val key: String = ZERO,
    val title: String = "Корень справочника",
    val path: List<String> = emptyList(),
)

data class CatalogDraft(
    val resource: String,
    var name: String = "",
    var fullName: String = "",
    var comment: String = "",
    var inn: String = "",
    var personType: String = "ЮрЛицо",
    var barcode: String = "",
    var service: Boolean = false,
    var address: String = "",
    var prefix: String = "",
    var folder: Selection = Selection(),
    var unit: Selection? = null,
    var ved: Selection? = null,
) {
    fun payload(): Row {
        if (name.trim().isEmpty()) throw AppError("Введите наименование.")
        if (!GUID.matches(folder.key)) throw AppError("Выберите корректную папку.")
        val result =
            linkedMapOf<String, Any?>(
                "Description" to name.trim(),
                "Комментарий" to comment.trim(),
                "Parent_Key" to folder.key,
                "IsFolder" to false,
            )
        when (resource) {
            "Контрагенты" -> {
                if (personType !in listOf("ЮрЛицо", "ФизЛицо"))
                    throw AppError("Неизвестный вид контрагента.")
                result += "НаименованиеПолное" to fullName.trim().ifEmpty { name.trim() }
                result += "ИНН" to inn.trim()
                result += "ЮрФизЛицо" to personType
            }

            "Номенклатура" -> {
                result += "НаименованиеПолное" to fullName.trim().ifEmpty { name.trim() }
                result += "Услуга" to service
                result += "Штрихкод" to barcode.trim()
                listOf("БазоваяЕдиницаИзмерения_Key" to unit, "ВЭД_Key" to ved).forEach {
                    (key, value) ->
                    if (value != null) {
                        if (!GUID.matches(value.key) || value.key == ZERO)
                            throw AppError("Некорректная ссылка: $key")
                        result[key] = value.key
                    }
                }
            }

            "Склады" -> {
                result += "Адрес" to address.trim()
                result += "Префикс" to prefix.trim()
            }

            else -> throw AppError("Создание этого объекта не поддерживается.")
        }
        return result
    }
}
