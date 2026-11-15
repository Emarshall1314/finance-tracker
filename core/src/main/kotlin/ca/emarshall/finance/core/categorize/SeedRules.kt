package ca.emarshall.finance.core.categorize

/**
 * The built-in rules: chains and public companies common in BC and Canada, plus words that say what a
 * place is ("sushi", "dental", "barber"). They live in code, not the database, so every app update brings
 * the new ones; bump [VERSION] when they change so existing transactions are re-sorted once.
 *
 * Priorities: a named chain (10) beats a describing word (2-6); bank row types, fees and transfers sit
 * above both (12-40); the user's own rules sit above everything (500+). At equal priority the longer
 * pattern wins, so "COSTCO GAS" beats "COSTCO".
 *
 * Nothing here names a person or anything from the owner's own statements.
 */
object SeedRules {
    const val VERSION = 3

    private val rules = ArrayList<Rule>()

    private fun add(type: MatchType, key: String, priority: Int, sign: Int, patterns: Array<out String>) {
        for (p in patterns) rules.add(Rule(pattern = p, matchType = type, categoryKey = key, priority = priority, amountSign = sign))
    }

    /** Whole words: a brand or an unambiguous word. */
    private fun word(key: String, vararg patterns: String, priority: Int = 10, sign: Int = 0) = add(MatchType.WORD, key, priority, sign, patterns)

    /** Start of a word: stems such as TACO (tacos), PHARMA (pharmacy), OPTOMETR (optometrist). */
    private fun stem(key: String, vararg patterns: String, priority: Int = 4, sign: Int = 0) = add(MatchType.PREFIX, key, priority, sign, patterns)

    /** Anywhere in the text: for patterns with punctuation, or bank wording. */
    private fun has(key: String, vararg patterns: String, priority: Int = 10, sign: Int = 0) = add(MatchType.CONTAINS, key, priority, sign, patterns)

    private fun regex(key: String, pattern: String, priority: Int = 10, sign: Int = 0) = add(MatchType.REGEX, key, priority, sign, arrayOf(pattern))

    init {
        // ------------------------------------------------------------------ Groceries
        word(
            "groceries", "SAVE ON FOODS", "SAVE-ON-FOODS", "SAVEONFOODS", "SUPERSTORE", "REAL CDN", "RCSS", "SAFEWAY", "NO FRILLS", "NOFRILLS",
            "WHOLE FOODS", "WHOLEFDS", "T&T", "T & T SUPERMARKET", "NESTERS", "FRESH ST", "FRESH STREET", "COOPERS FOODS", "QUALITY FOODS",
            "URBAN FARE", "IGA", "H MART", "HMART", "FRESHCO", "WALMART SUPERCENTER", "WAL-MART SUPERCENTER", "THRIFTY FOODS", "THRIFTYS",
            "CHOICES MARKET", "STONG'S", "STONGS", "MEINHARDT", "PRICESMART", "PRICE SMART", "INDEPENDENT GROCER", "LOBLAWS", "SOBEYS",
            "FOOD BASICS", "BUY-LOW", "BUY LOW FOODS", "SUPER VALU", "KIN'S FARM", "KINS FARM", "PERSIA FOODS", "SUNRISE MARKET",
            "DONALD'S MARKET", "FAMOUS FOODS", "EAST WEST MARKET", "88 SUPERMARKET", "OSAKA SUPERMARKET", "SUNGIVEN", "FOODY WORLD",
            "ASSI MARKET", "HANNAM", "FRUITICANA", "SPUD.CA", "INSTACART", "VOILA", "HELLOFRESH", "HELLO FRESH", "GOODFOOD", "CHEFS PLATE",
            "M&M FOOD MARKET", "BULK BARN", "NATURE'S FARE", "POMME NATURAL", "COUNTRY GROCER", "FAIRWAY MARKET", "RED BARN MARKET",
            "AG FOODS", "FAMILY FOODS", "COUNTRY MARKET", "FARMERS MARKET", "FARM MARKET", "CO-OP FOOD", "LONGO'S", "FARM BOY",
            "PROVIGO", "ZEHRS", "FORTINOS", "VALU-MART", "SUPER C", "ADONIS", "NATIONS FRESH", "PATEL BROTHERS",
        )
        regex("groceries", "\\bCOSTCO\\b(?!.*GAS)")
        stem("groceries", "SUPERMARKET", "GROCER", "PRODUCE", "BUTCHER", "ORGANIC", priority = 5)
        word("groceries", "FOODS", "MEATS", "MEAT", "FISH MARKET", "FOOD MARKET", "FOOD STORE", priority = 3)
        stem("groceries", "FARM", priority = 2)
        word("groceries", "MARKET", priority = 1)

        // ------------------------------------------------------------------ Dining & coffee
        word(
            "dining", "TIM HORTONS", "TIMHORTONS", "TIMS", "STARBUCKS", "A&W", "A & W", "WHITE SPOT", "TRIPLE O", "TRIPLE O'S", "BLENZ",
            "JJ BEAN", "SUBWAY", "DOORDASH", "SKIPTHEDISHES", "SKIP THE DISHES", "FANTUAN", "RITUAL", "BOSTON PIZZA", "PIZZA HUT", "PANAGO",
            "LITTLE CAESARS", "FRESHSLICE", "FRESH SLICE", "MEGABITE", "UNCLE FATIH", "KFC", "POPEYES", "MARY BROWN", "BURGER KING",
            "DAIRY QUEEN", "DQ", "FIVE GUYS", "FATBURGER", "TACO BELL", "TACO DEL MAR", "CHIPOTLE", "QUESADA", "MUCHO BURRITO", "CACTUS CLUB",
            "EARLS", "JOEY", "BROWNS SOCIALHOUSE", "BROWNS CRAFTHOUSE", "THE KEG", "KEG STEAKHOUSE", "MILESTONES", "RED ROBIN", "IHOP",
            "DE DUTCH", "SWISS CHALET", "OLIVE GARDEN", "OLD SPAGHETTI FACTORY", "SHARK CLUB", "TAP & BARREL", "TAP AND BARREL",
            "CRAFT BEER MARKET", "STEAMWORKS", "COBS BREAD", "TERRA BREADS", "PUREBREAD", "KRISPY KREME", "CARTEMS", "BASKIN", "MARBLE SLAB",
            "YOGEN FRUZ", "QOOLA", "BOOSTER JUICE", "JUGO JUICE", "JAMBA", "CHATIME", "GONG CHA", "SHARETEA", "COCO FRESH TEA", "THE ALLEY",
            "XING FU TANG", "PRESOTEA", "FRESHII", "THAI EXPRESS", "EDO JAPAN", "MANCHU WOK", "NEW YORK FRIES", "CINNABON", "NOODLEBOX",
            "JAPADOG", "MEAT & BREAD", "TACOFINO", "ARTIGIANO", "WAVES COFFEE", "SECOND CUP", "MCCAFE", "MEAT AND BREAD", "RAIN OR SHINE",
            "EARNEST ICE CREAM", "PARALLEL 49", "33 ACRES", "FOOD COURT", "FOOD TRUCK", "FISH & CHIPS", "FISH AND CHIPS", "HOT POT",
            "DIM SUM", "BANH MI", "BUBBLE TEA", "ICE CREAM", "SPORTS BAR", "CIRCLE K", "MAC'S CONVENIENCE", "PIZZA PIZZA", "PIZZA 73",
            "NANDO'S", "NANDOS", "OSMOW", "PITA PIT", "MR SUB", "FIREHOUSE SUBS", "JERSEY MIKE", "JIMMY JOHN", "QDOBA", "PANERA",
            "CHICK-FIL-A", "SHAKE SHACK", "IN-N-OUT", "DUNKIN", "PEET'S", "DUTCH BROS", "BEAVERTAILS", "COLD STONE", "CRUMBL",
            "WOK BOX", "OPA!", "HOT DOG", "HOTDOG", "CORN DOG", "ROGERS CHOCOLATES", "ROGERS' CHOCOLATES", "APPLEBEE'S", "APPLEBEES", "ST-HUBERT", "ST HUBERT", "HARVEY'S", "HARVEYS", "MONTANA'S", "MONTANAS", "KELSEY", "EAST SIDE MARIO",
            "RICKY'S", "SMITTY'S", "SMITTYS", "COUNTRY STYLE", "ROBIN'S DONUTS", "COFFEE TIME", "ORIGINAL JOE", "STATE & MAIN",
        )
        stem("dining", "MCDONALD", "WENDY", "DOMINO", "DENNY", "MOXIE", "MENCHIE", "PAPA JOHN", "CARL'S JR", "CHURCH'S CHICKEN", priority = 10)
        has("dining", "UBER EATS", "UBEREATS", "UBER *EATS", "UBER* EATS", "DD *", "SKIP*", priority = 20)
        has("dining", "TST*", "TST-", priority = 3)
        stem(
            "dining", "RESTAURANT", "RESTA", "CAFE", "CAFFE", "COFF", "ESPRESSO", "ROASTER", "BAKERY", "BAKESHOP", "PATISSERIE",
            "BOULANGERIE", "DONUT", "DOUGHNUT", "BAGEL", "GELAT", "SMOOTHIE", "JUICE", "PIZZ", "SUSHI", "SASHIMI", "RAMEN", "UDON", "IZAKAYA",
            "NOODLE", "DUMPLING", "WONTON", "CONGEE", "HOTPOT", "TERIYAKI", "BENTO", "SHAWARMA", "DONAIR", "FALAFEL", "KEBAB", "KABOB", "SOUVLAKI",
            "TANDOORI", "CURRY", "BIRYANI", "TAQUERIA", "BURRITO", "CANTINA", "TRATTORIA", "OSTERIA", "RISTORANTE", "STEAKHOUSE",
            "BURGER", "POUTINE", "SANDWICH", "CATERING", "CUISINE", "BRASSERIE", "CREPE", "WAFFLE", "PANCAKE", "BRUNCH", "BREAKFAST",
            "BREWHOUSE", "BREWPUB", "BREWING", "BREWERY", "TAPHOUSE", "TAPROOM", "ALEHOUSE", "TAVERN", "BISTRO", "GRILL", "EATERY", "DINER",
            "CHICKEN", "BAGUETTE", "CANTEEN", "CAFETERIA", "CONCESSION", "SNACK", "CHOCOLAT", "CONFECTION", "SHAWERMA", "PUPUSA", "EMPANADA",
            "SAMOSA", "ROTI", "CEVICHE", "TEPPAN", "YAKITORI", "OKONOMI", "KATSU", "GYOZA", "BIBIMBAP", "PIEROGI", "SCHNITZEL",
        )
        word(
            "dining", "PUB", "PHO", "THAI", "POKE", "BBQ", "BARBECUE", "KOREAN", "VIETNAMESE", "MEXICAN", "GREEK", "INDIAN", "JAPANESE",
            "CHINESE", "ITALIAN", "TAPAS", "TACO", "TACOS", "RESTO", "PASTA", "STEAK", "WINGS", "OYSTER", "SEAFOOD", "SUBS", "DELI", "EATS", "LOUNGE", "BOBA", "GYRO",
            "GYROS", "WOK", "BREAD", "FRIES", "SALAD", "SOUP", "VEGAN", "VEGETARIAN", "FRIED CHICKEN", "DINING", "LUNCH", "DINNER", "MEALS",
            priority = 4,
        )
        word("dining", "KITCHEN", "TEA", "7-ELEVEN", "7 ELEVEN", "7-11", "CONVENIENCE", "VENDING", priority = 3)
        word("dining", "BAR", "FOOD", priority = 2)

        // ------------------------------------------------------------------ Liquor & cannabis
        word(
            "alcohol", "TOWNSHIP7", "TOWNSHIP 7", "BC LIQUOR", "BCLS", "BCL", "LEGACY LIQUOR", "EVERYTHING WINE", "MARQUIS WINE", "JAK'S", "BC CANNABIS", "CITY CANNABIS",
            "KIARO", "EVERGREEN CANNABIS", "MUSE CANNABIS", "LCBO", "SAQ", "THE BEER STORE", "BEER STORE", "COLD BEER", "WINE RACK",
            "LIQUOR DEPOT", "LIQUOR MART", "LIQUOR BARN", "TOKYO SMOKE", "FIRE & FLOWER", "SPIRITLEAF", "ONTARIO CANNABIS",
        )
        stem("alcohol", "LIQUOR", "CANNABIS", priority = 8)
        stem("alcohol", "WINERY", "DISTILLER", "DISPENSAR", "TOBACCO", "CIGAR", "VAPE", priority = 5)
        word("alcohol", "WINE", "WINES", "BEER", "SPIRITS", "SMOKE SHOP", priority = 4)

        // ------------------------------------------------------------------ Fuel and EV charging
        word(
            "fuel", "PETRO-CANADA", "PETRO CANADA", "PETROCAN", "PETRO-CAN", "SHELL", "ESSO", "CHEVRON", "HUSKY", "MOBIL", "PARKLAND", "CENTEX",
            "CANCO", "FAS GAS", "ULTRAMAR", "IRVING OIL", "SUNOCO", "SUPER SAVE GAS", "SUPERSAVE GAS", "TEMPO GAS", "CO-OP GAS", "COOP GAS",
            "CHARGEPOINT", "FLO SERVICES", "ELECTRIFY CANADA", "SUPERCHARGER", "EV CHARGING", "GAS STATION", "GAS N GO", "MOHAWK",
            "PETRO-PASS", "CARDLOCK", "COUCHE-TARD", "MACEWEN", "NORTH ATLANTIC", "WILSON FUEL", "HARNOIS", "CREVIER", "VALERO",
            "EXXON", "TEXACO", "CONOCO", "PHILLIPS 66", "MARATHON PETRO", "CIRCLE K GAS",
        )
        word("fuel", "PIONEER", "ARCO", "DOMO", priority = 6)
        has("fuel", "COSTCO GAS", "CANADIAN TIRE GAS", "CDN TIRE GAS", "GAS BAR", "GASBAR", priority = 20)
        regex("fuel", "\\bCHV\\s?\\d")
        stem("fuel", "FUEL", "GASOLINE", "PETROLEUM", "DIESEL", priority = 4)
        word("fuel", "GAS", priority = 3)

        // ------------------------------------------------------------------ Auto
        word(
            "auto", "KAL TIRE", "MIDAS", "JIFFY LUBE", "NAPA", "CAR WASH", "CARWASH", "PARTSOURCE", "LORDCO", "MR LUBE", "MR. LUBE",
            "GREAT CANADIAN OIL CHANGE", "OK TIRE", "FOUNTAIN TIRE", "BIG O TIRES", "TIRELAND", "SPEEDY GLASS", "CRAFTSMAN COLLISION",
            "BOYD AUTOBODY", "U-HAUL", "UHAUL", "OIL CHANGE", "AUTO PARTS", "AUTO REPAIR", "AUTO SERVICE", "AUTO BODY", "AUTO GLASS",
            "AUTOZONE", "O'REILLY AUTO", "CARQUEST", "BUMPER TO BUMPER", "PRINCESS AUTO", "CANADIAN TIRE AUTO", "MISTER TRANSMISSION",
            "MEINEKE", "FIX AUTO", "CARSTAR", "APPLE AUTO GLASS", "PETRO-CANADA CAR WASH",
        )
        word("auto", "CANADIAN TIRE", "CDN TIRE", priority = 5)
        stem("auto", "AUTOMOTIVE", "AUTOBODY", "AUTOGLASS", "COLLISION", "MECHANIC", "MUFFLER", "BRAKE", "TRANSMISSION", "TIRE", "DETAILING", "MOTORCYCLE", "TOWING", priority = 4)
        word(
            "auto", "TOYOTA", "HONDA", "MAZDA", "HYUNDAI", "KIA", "NISSAN", "SUBARU", "VOLKSWAGEN", "FORD", "CHEVROLET", "TESLA", "DODGE", "JEEP",
            "BMW", "MERCEDES", "AUDI", "LEXUS", "ACURA", "MOTORS", "LUBE", "AIRCARE", priority = 4,
        )
        word("auto", "AUTO", priority = 3)

        // ------------------------------------------------------------------ Transit, taxis and parking
        word(
            "transit", "COMPASS", "TRANSLINK", "TLNK", "BC TRANSIT", "EVO CAR SHARE", "EVO CARSHARE", "MODO", "ZIPCAR", "LYFT", "IMPARK", "EASYPARK",
            "EASY PARK", "PAYBYPHONE", "PAY BY PHONE", "HANGTAG", "INDIGO PARK", "DIAMOND PARKING", "ADVANCED PARKING", "WESTPARK",
            "PRECISE PARKLINK", "WEST COAST EXPRESS", "YELLOW CAB", "BLACK TOP", "MACLURE", "BONNY'S TAXI", "MOBI BY", "MOBI BIKE", "TTC",
            "PRESTO", "GO TRANSIT", "OC TRANSPO", "STM", "CALGARY TRANSIT", "EDMONTON TRANSIT", "GREEN P", "SPOTHERO", "PARKMOBILE",
            "PARKWHIZ", "REEF PARKING", "LAZ PARKING", "COMMUNAUTO",
        )
        stem("transit", "IMPARK", "EASYPARK", "PAYBYPHONE", "TRANSLINK", "COMPASS", priority = 10) // often followed by glued-on lot numbers
        word("transit", "EVO", priority = 8)
        stem("transit", "UBER", "HONK", priority = 5)
        stem("transit", "PARKING", "PARKADE", "TAXI", priority = 5)
        word("transit", "TRANSIT", "CAB", "TOLL", "CARSHARE", "CAR SHARE", "RIDESHARE", "METER", priority = 4)

        // ------------------------------------------------------------------ Travel
        word(
            "travel", "AIR CANADA", "AIRCANADA", "WESTJET", "FLAIR", "PORTER AIR", "LYNX AIR", "SWOOP", "AIR TRANSAT", "HARBOUR AIR",
            "PACIFIC COASTAL", "AIRBNB", "VRBO", "EXPEDIA", "BOOKING.COM", "HOTELS.COM", "PRICELINE", "TRIVAGO", "HOPPER", "AGODA", "TRIP.COM",
            "MARRIOTT", "HILTON", "HYATT", "SHERATON", "WESTIN", "FAIRMONT", "BEST WESTERN", "SANDMAN", "HOLIDAY INN", "RAMADA", "SUPER 8",
            "COMFORT INN", "DAYS INN", "BC FERRIES", "BCF", "VIA RAIL", "AMTRAK", "ROCKY MOUNTAINEER", "HERTZ", "AVIS", "BUDGET RENT",
            "ENTERPRISE RENT", "NATIONAL CAR", "TURO", "BC PARKS", "PARKS CANADA", "DISCOVER CAMPING", "PARK'N FLY", "GREYHOUND", "FLIXBUS",
            "RIDER EXPRESS", "EBUS", "DUTY FREE", "NEXUS", "FOUR SEASONS", "SHANGRI-LA", "DELTA HOTELS", "COAST HOTELS", "ACCENT INNS",
            "MOTEL 6", "TRAVELODGE", "QUALITY INN", "HAMPTON INN", "RESIDENCE INN", "COURTYARD BY", "DOUBLETREE", "RADISSON",
            "WYNDHAM", "SUNWING", "UNITED AIRLINES", "DELTA AIR", "ALASKA AIR", "AMERICAN AIRLINES", "SOUTHWEST AIR", "LUFTHANSA",
            "BRITISH AIRWAYS", "KLM", "AIR FRANCE", "CATHAY", "JAPAN AIRLINES", "EVA AIR", "KOREAN AIR", "PHILIPPINE AIR",
            "CAR RENTAL", "RENT A CAR", "RENT-A-CAR", "DOLLAR RENT", "THRIFTY CAR", "ALAMO",
        )
        stem("travel", "AIRLINE", "AIRWAYS", "HOTEL", "MOTEL", "HOSTEL", "RESORT", "CAMPGROUND", "CAMPSITE", "CRUISE", "FERRIES", priority = 5)
        word("travel", "INN", "SUITES", "LODGE", "AIRPORT", "YVR", "FERRY", "CAMPING", "RV PARK", "TOURS", "TRAVEL", priority = 3)

        // ------------------------------------------------------------------ Shopping
        word(
            "shopping", "AMAZON", "AMZN", "WINNERS", "MARSHALLS", "BEST BUY", "BESTBUY", "HUDSON'S BAY", "HUDSONS BAY", "THE BAY", "SPORT CHEK",
            "SPORTCHEK", "MEC", "MOUNTAIN EQUIPMENT", "ARC'TERYX", "ARCTERYX", "PATAGONIA", "NORTH FACE", "ATMOSPHERE", "VALHALLA PURE",
            "DOLLARAMA", "DOLLAR TREE", "DOLLAR STORE", "MARK'S", "MARKS WORK", "MUJI", "ZARA", "UNIQLO", "LULULEMON", "H&M", "H & M", "GAP",
            "OLD NAVY", "BANANA REPUBLIC", "ARITZIA", "ROOTS", "SIMONS", "NORDSTROM", "APPLE STORE", "MICROSOFT STORE", "STAPLES", "THE SOURCE",
            "MEMORY EXPRESS", "CANADA COMPUTERS", "NEWEGG", "VISIONS ELECTRONICS", "INDIGO", "CHAPTERS", "BOOK WAREHOUSE", "EBAY", "ETSY",
            "ALIEXPRESS", "ALIBABA", "TEMU", "SHEIN", "FOOT LOCKER", "SPORTING LIFE", "NIKE", "ADIDAS", "ALDO", "SOFTMOC", "PANDORA",
            "MICHAEL HILL", "PEOPLES JEWELLERS", "SWAROVSKI", "VALUE VILLAGE", "MICHAELS", "DESERRES", "OPUS ART", "TOYS R US", "LEGO",
            "GIANT TIGER", "ARMY & NAVY", "CANADA GOOSE", "POKEMON", "PARTY CITY", "CANADA POST", "FEDEX", "PUROLATOR", "UPS STORE", "DHL",
            "SALVATION ARMY THRIFT", "DAISO", "MINISO", "OOMOMO", "URBAN OUTFITTERS", "AMERICAN EAGLE", "HOLLISTER", "ABERCROMBIE",
            "FOREVER 21", "GARAGE CLOTHING", "DYNAMITE", "RW&CO", "REITMANS", "LA SENZA", "VICTORIA'S SECRET", "LEVI'S", "LEVIS", "GUESS",
            "TOMMY HILFIGER", "CALVIN KLEIN", "KATE SPADE", "MICHAEL KORS", "BIRKS", "SUNGLASS HUT",
            "DECATHLON", "TILLEY", "BLUNDSTONE", "DR MARTENS", "TIMBERLAND", "NEW BALANCE", "UNDER ARMOUR", "PUMA", "CHAMPS",
            "JD SPORTS", "LIDS", "HOT TOPIC", "CLAIRE'S", "ARDENE", "BLUENOTES", "BOATHOUSE", "WEST 49", "ZUMIEZ",
            "PACSUN", "TJ MAXX", "TARGET", "KOHL'S", "MACY'S", "SAKS", "HOLT RENFREW", "HARRY ROSEN", "TIP TOP",
            "MOORES", "LONG & MCQUADE", "TOM LEE MUSIC", "GUITAR CENTER", "LONDON DRUGS PHOTO", "HENRY'S", "THE CAMERA STORE",
            "BROADWAY CAMERA", "KERRISDALE CAMERAS", "AMAZON.CA", "WAYFAIR.CA", "COSTCO.CA", "WALMART.CA", "SHOP.APP",
        )
        word("shopping", "WALMART", "WAL-MART", "VANS", priority = 5)
        has("shopping", "SP *", "SP*", "SHOPIFY", priority = 2)
        stem("shopping", "CLOTHING", "APPAREL", "BOUTIQUE", "FASHION", "JEWEL", "FOOTWEAR", "THRIFT", "CONSIGNMENT", "BOOKSTORE", "ELECTRONIC", "OUTFITTER", "DEPARTMENT STORE", "STATIONER", priority = 4)
        word("shopping", "SHOES", "BOOKS", "DOLLAR", "TOYS", "OUTLET", "MALL", "CAMERA", "PHOTO", "COMICS", "HOBBY", "HOBBIES", "CRAFTS", "FABRIC", "FABRICS", priority = 3)
        word("shopping", "APPLE", priority = 3)

        // ------------------------------------------------------------------ Home
        word(
            "home", "HOME DEPOT", "RONA", "LOWE'S", "LOWES", "HOME HARDWARE", "HOMEHARDWARE", "IKEA", "HOMESENSE", "BED BATH", "STRUCTUBE",
            "CRATE & BARREL", "CRATE AND BARREL", "WEST ELM", "POTTERY BARN", "WAYFAIR", "THE BRICK", "LEON'S", "SLEEP COUNTRY", "JYSK",
            "KITCHEN STUFF PLUS", "URBAN BARN", "EQ3", "ACE HARDWARE", "WINDSOR PLYWOOD", "CLOVERDALE PAINT", "BENJAMIN MOORE", "SHERWIN",
            "DULUX", "LEE VALLEY", "KMS TOOLS", "GARDENWORKS", "ART KNAPP", "PUBLIC STORAGE", "MAPLE LEAF STORAGE", "MOLLY MAID",
            "HOME OUTFITTERS", "BOUCLAIR", "LINEN CHEST", "RENO-DEPOT", "TIMBER MART", "TIMBR MART", "BMR", "CASTLE BUILDING",
            "ASHLEY FURNITURE", "DUFRESNE", "CITY FURNITURE", "SCAN DESIGNS", "INSPIRATION FURNITURE", "MOE'S HOME", "PIER 1",
            "WILLIAMS-SONOMA", "WILLIAMS SONOMA", "RESTORATION HARDWARE", "CB2", "ARTICLE.COM", "CASPER", "ENDY", "DYSON",
            "TRAIL APPLIANCES", "COAST APPLIANCES", "ROOMS TO GO",
        )
        stem("home", "HARDWARE", "LUMBER", "PLUMBING", "FURNITURE", "MATTRESS", "APPLIANCE", "FLOORING", "LOCKSMITH", "NURSERY", "GARDEN CENT", "PEST CONTROL", "CARPET", "UPHOLSTER", "RENOVATION", "ROOFING", "HVAC", priority = 4)
        word("home", "STORAGE", "MOVERS", "MOVING", "HOUSEWARES", "PAINT", "PAINTS", "TOOLS", "HANDYMAN", "ELECTRICIAN", "PLUMBER", "CONTRACTOR", priority = 3)

        // ------------------------------------------------------------------ Bills & utilities
        word("bills", "BC HYDRO", "B.C. HYDRO", "BCHYDRO", "FORTISBC", "FORTIS BC", "METRO VANCOUVER", "ENMAX", "EPCOR", "ENBRIDGE", "HYDRO ONE", "NATURAL GAS",
            "HYDRO QUEBEC", "HYDRO-QUEBEC", "TORONTO HYDRO", "ATCO", "DIRECT ENERGY", "SASKPOWER", "SASKENERGY", "MANITOBA HYDRO", "NOVA SCOTIA POWER", "NB POWER",
            "ALECTRA", "PACIFIC NORTHERN GAS", "CORIX")
        has("bills", "HYDRO BILL", "UTILITY BILL", "GAS BILL", "WATER BILL", "RENT/LEASE", "MORTGAGE PAYMENT", "LOAN PAYMENT", priority = 15)
        word("bills", "STRATA", "UTILITIES", "UTILITY", "HYDRO", "LANDLORD", "PROPERTY MANAGEMENT", "MORTGAGE", "CONDO FEES", "MAINTENANCE FEES", priority = 4)
        has("bills", "CITY OF ", "DISTRICT OF ", "TOWN OF ", "MUNICIPALITY OF ", priority = 3)
        word("bills", "RENT", priority = 2, sign = -1)

        // ------------------------------------------------------------------ Phone & internet
        word(
            "phone", "TELUS", "ROGERS", "BELL CANADA", "BELL MOBILITY", "FREEDOM MOBILE", "FIDO", "KOODO", "VIRGIN PLUS", "VIRGIN MOBILE",
            "PUBLIC MOBILE", "SHAW", "NOVUS", "CHATR", "LUCKY MOBILE", "FIZZ", "OXIO", "TEKSAVVY", "STARLINK", "EASTLINK", "VIDEOTRON",
            "SASKTEL", "COGECO", "DISTRIBUTEL", "START.CA", "CARRYTEL", "EBOX", "BEANFIELD", "XPLORE", "XPLORNET", "MINT MOBILE",
            "T-MOBILE", "VERIZON", "AT&T",
        )
        has("phone", "TELEPHONE BILL", "CABLE BILL", "INTERNET BILL", priority = 15)
        word("phone", "BELL", "WIRELESS", "INTERNET", "TELECOM", "MOBILITY", "BROADBAND", priority = 3)

        // ------------------------------------------------------------------ Insurance
        word(
            "insurance", "ICBC", "INSURANCE CORPORATION OF BC", "BCAA", "SQUARE ONE", "MANULIFE", "SUN LIFE", "SUNLIFE", "PACIFIC BLUE CROSS",
            "BLUE CROSS", "CANADA LIFE", "GREAT-WEST LIFE", "INTACT", "AVIVA", "WAWANESA", "BELAIRDIRECT", "SONNET", "COOPERATORS",
            "CO-OPERATORS", "ALLSTATE", "STATE FARM", "WESTLAND", "HUB INTERNATIONAL", "AUTOPLAN", "DESJARDINS INS", "TD INSURANCE",
            "RBC INSURANCE", "INDUSTRIAL ALLIANCE", "IA FINANCIAL", "EMPIRE LIFE", "EQUITABLE LIFE", "GREEN SHIELD", "SSQ", "CAA",
            "DEFINITY", "GORE MUTUAL", "PEMBRIDGE", "CHUBB", "POLICYME", "APOLLO INSURANCE",
        )
        stem("insurance", "INSURANCE", "INSURE", "ASSURANCE", priority = 6)

        // ------------------------------------------------------------------ Health & pharmacy
        word(
            "health", "SHOPPERS DRUG MART", "SHOPPERS", "SDM", "PHARMASAVE", "REXALL", "PEOPLES DRUG MART", "DRUG MART", "LENSCRAFTERS", "CLEARLY",
            "LIFELABS", "GNC", "POPEYE'S SUPPLEMENTS", "MSP", "PHARMACARE", "EYE CARE", "EYECARE", "WELL.CA", "JEAN COUTU", "UNIPRIX",
            "GUARDIAN PHARMACY", "IDA PHARMACY", "PHARMAPRIX", "MEDICINE SHOPPE", "COSTCO PHARMACY", "HAKIM OPTICAL", "SPECSAVERS",
            "WARBY PARKER", "FYIDOCTORS", "IRIS OPTOM", "DYNACARE", "MAPLE HEALTH", "TELUS HEALTH", "FELIX HEALTH", "VITAMIN SHOP",
            "SUPPLEMENT KING", "BODY ENERGY CLUB", "FINLANDIA", "WALK-IN CLINIC",
        )
        word("health", "LONDON DRUGS", priority = 5)
        stem(
            "health", "PHARMA", "DENTAL", "DENTIST", "ORTHODONT", "PHYSIO", "CHIROPRACT", "MASSAGE", "ACUPUNCTUR", "NATUROPATH", "OPTOMETR", "OPTICAL",
            "MEDICAL", "HOSPITAL", "THERAP", "COUNSEL", "PSYCHOLOG", "PSYCHIATR", "VITAMIN", "SUPPLEMENT", "DRUGSTORE", "DERMATOLOG", "PODIATR",
            "ORTHOTIC", "HEARING", "KINESIO", "OSTEOPATH", "PERIODONT", "ENDODONT", "DENTURE", "RADIOLOG", "PEDIATR", "DOCTOR", "PHYSICIAN",
            "SURGERY", "SURGEON", "IMAGING", "LABORATOR", priority = 5,
        )
        word("health", "CLINIC", "DRUGS", "RMT", "WELLNESS", "DDS", "DMD", "VISION", "EYEWEAR", "OPTICIANS", "MIDWIFE", "DOULA", "FERTILITY", "LASIK", priority = 4)
        stem("health", "HEALTH", priority = 3)

        // ------------------------------------------------------------------ Personal care
        word(
            "personal_care", "GREAT CLIPS", "SUPERCUTS", "CHATTERS", "TOMMY GUN", "SEPHORA", "BATH & BODY", "BODY SHOP", "SAJE", "LUSH",
            "EUROPEAN WAX", "DRY CLEAN", "DRYCLEAN", "COIN LAUNDRY", "ULTA", "MAC COSMETICS", "KIEHL", "AVEDA", "AESOP", "L'OCCITANE",
            "RITUALS", "FIRST CHOICE HAIR", "MAGICUTS", "SPORT CLIPS", "FANTASTIC SAMS", "MERLE NORMAN", "SALLY BEAUTY", "TRADE SECRETS",
            "LASER CLINIC", "DERMALOGICA", "BLO BLOW", "DRYBAR", "CURLY HAIR", "MASTERMIND STUDIO", "FUZZ WAX", "WAXON", "THE TEN SPOT",
        )
        stem(
            "personal_care", "SALON", "BARBER", "HAIR", "BEAUTY", "NAIL", "LASH", "ESTHETIC", "AESTHETIC", "COSMETIC", "TATTOO", "PIERCING",
            "LAUNDR", "TAILOR", "ALTERATION", "SKINCARE", "MANICURE", "PEDICURE", "THREADING", "ELECTROLYSIS", "MAKEUP", "FRAGRANCE", "PERFUME",
            "GROOMING LOUNGE", "SHOE REPAIR", "COBBLER",
        )
        word("personal_care", "SPA", "BROW", "BROWS", "WAX", "WAXING", "CLEANERS", "FACIAL", "FACIALS", "SKIN", "STYLIST", "BLOWOUT", priority = 4)

        // ------------------------------------------------------------------ Pets
        word("pets", "PETSMART", "PET SMART", "PETCO", "PET VALU", "PETVALU", "BOSLEY'S", "BOSLEYS", "TISOL", "PETLAND", "GLOBAL PET FOODS", "ANIMAL HOSPITAL",
            "ANIMAL CLINIC", "REN'S PETS", "MONDOU", "CHEWY", "HOMES ALIVE", "BONE & BISCUIT", "WOOFY", "TRUPANION", "PETSECURE", "PETS PLUS US", "ROVER.COM",
            "DOG GROOMING", "PET GROOMING", "DOG DAYCARE", "DOGGY DAYCARE", "PET FOOD")
        stem("pets", "VETERINAR", "KENNEL", priority = 6)
        word("pets", "PET", "PETS", "VET", "PUPPY", "CAT CLINIC", "CANINE", "FELINE", priority = 5)
        word("pets", "DOG", "DOGS", "ANIMAL", "ANIMALS", "PAWS", priority = 3)

        // ------------------------------------------------------------------ Sports & fitness
        word(
            "recreation", "GOODLIFE", "ANYTIME FITNESS", "PLANET FITNESS", "STEVE NASH", "FITNESS WORLD", "CLUB16", "TREVOR LINDEN", "ORANGETHEORY",
            "F45", "CROSSFIT", "YYOGA", "THE HIVE", "GROUSE MOUNTAIN", "CYPRESS MOUNTAIN", "MT SEYMOUR", "MOUNT SEYMOUR", "WHISTLER BLACKCOMB",
            "VAIL RESORTS", "EPIC PASS", "LIFT TICKET", "DRIVING RANGE", "MARTIAL ARTS", "JIU JITSU", "MUAY THAI", "REC CENTRE", "REC CENTER",
            "COMMUNITY CENTRE", "COMMUNITY CENTER", "YMCA", "YWCA", "PARK BOARD", "RUNNING ROOM", "CABELA", "BASS PRO", "SUN PEAKS", "BIG WHITE",
            "SILVERSTAR", "REVELSTOKE MOUNTAIN", "KICKING HORSE", "MANNING PARK", "SASQUATCH MOUNTAIN", "HEMLOCK", "RED MOUNTAIN",
            "LAKE LOUISE SKI", "SUNSHINE VILLAGE", "IKON PASS", "CLASSPASS", "MINDBODY", "EQUINOX", "SOULCYCLE", "PELOTON", "LA FITNESS",
            "GOLD'S GYM", "WORLD GYM", "SNAP FITNESS", "FIT4LESS", "ECONOFITNESS", "HOT YOGA", "MODO YOGA", "ORO YOGA", "GOLF TOWN",
            "GOLFTOWN", "TOPGOLF", "COMOR", "PACIFIC BOARDER", "WEST COAST KAYAK", "DEEP COVE KAYAK", "ROCKY MOUNTAIN BICYCLES",
            "TRAIL FORKS", "TRAILFORKS", "STRAVA", "ZWIFT", "ALLTRAILS", "BC SOCCER", "HOCKEY CANADA", "SPORTS LEAGUE", "URBAN REC",
            "GOLF COURSE", "GOLF CLUB", "GOLF & COUNTRY", "COUNTRY CLUB",
        )
        stem(
            "recreation", "CLIMB", "BOULDER", "SNOWBOARD", "GOLF", "TENNIS", "BADMINTON", "PICKLEBALL", "SWIM",
            "AQUATIC", "SKATE", "HOCKEY", "SOCCER", "BASEBALL", "BASKETBALL", "VOLLEYBALL", "KARATE", "TAEKWONDO", "BOXING", "DOJO", "RECREATION", "BICYCLE",
            "TRIATHLON", "MARATHON", "KAYAK", "PADDLE", "SURFING", "SURFBOARD", "FISHING", "ARCHERY", "CURLING", "GYMNAST", "ATHLETIC", "LACROSSE", "RUGBY",
            "SOFTBALL", "ULTIMATE FRISBEE", "ROWING", "SAILING", "SCUBA", "EQUESTRIAN", "KICKBOX", "SQUASH CLUB", "RACQUET",
        )
        stem("recreation", "GYM", "FITNESS", "YOGA", "PILATES", priority = 5)
        word("recreation", "SKI", "SPIN", "BARRE", "RINK", "DANCE", "CYCLE", "CYCLES", "OUTDOORS", "SURF", "SPORTSPLEX", "FIELDHOUSE", priority = 4)
        stem("recreation", "BIKE", "SPORT", priority = 3)

        // ------------------------------------------------------------------ Entertainment
        word(
            "entertainment", "CINEPLEX", "FAMOUS PLAYER", "FAMOUS PLAYERS", "LANDMARK CINEMA", "RIO THEATRE", "VIFF", "TICKETMASTER", "EVENTBRITE",
            "STUBHUB", "SEATGEEK", "LIVE NATION", "ROGERS ARENA", "BC PLACE", "CANUCKS", "WHITECAPS", "BC LIONS", "PNE", "PLAYLAND",
            "SCIENCE WORLD", "VANCOUVER AQUARIUM", "STEAMGAMES", "STEAMPOWERED", "STEAM PURCHASE", "STEAM GAMES", "VALVE", "PLAYSTATION",
            "NINTENDO", "XBOX", "EPIC GAMES", "RIOT GAMES", "BLIZZARD", "GOG.COM", "TWITCH", "GAMESTOP", "EB GAMES", "ESCAPE ROOM", "LASER TAG",
            "BCLC", "PLAYNOW", "ART GALLERY", "POOL HALL", "MINI GOLF", "SCOTIABANK ARENA", "ROGERS CENTRE", "BELL CENTRE", "SCOTIABANK THEATRE",
            "IMAX", "THE REC ROOM", "REC ROOM", "DAVE & BUSTER", "CHUCK E", "SKY ZONE", "EXTREME AIR", "CASTLE FUN PARK", "CULTUS LAKE",
            "CAPILANO SUSPENSION", "SEA TO SKY GONDOLA", "BUTCHART", "ROYAL BC MUSEUM", "TELUS WORLD", "CALGARY STAMPEDE", "CNE",
            "RIPLEY'S", "CN TOWER", "LEGOLAND", "DISNEYLAND", "UNIVERSAL STUDIOS", "SIX FLAGS", "SEAWORLD", "AXS", "TICKETWEB",
            "SHOWPASS", "TIXR", "DICE.FM", "BANDSINTOWN", "FANDANGO", "DRAFTKINGS", "FANDUEL", "BET365", "POKERSTARS", "ROBLOX",
            "MINECRAFT", "MOJANG", "ELECTRONIC ARTS", "EA SPORTS", "UBISOFT", "ACTIVISION", "SUPERCELL", "NIANTIC", "OCULUS", "META QUEST",
        )
        stem("entertainment", "CINEMA", "THEATRE", "THEATER", "MUSEUM", "CONCERT", "FESTIVAL", "ARCADE", "BOWLING", "BILLIARD", "KARAOKE", "TRAMPOLINE", "CASINO", "LOTTER", "LOTTO", "NIGHTCLUB", "COMEDY", "TICKET", "AMUSEMENT", "WATERPARK", "WATERSLIDE", "PAINTBALL", "GO KART", "GO-KART", "AXE THROW", priority = 4)
        word("entertainment", "GALLERY", "ZOO", "GAMES", "GAMING", "AQUARIUM", "PLANETARIUM", "OBSERVATORY", "CIRCUS", "OPERA", "SYMPHONY", "ORCHESTRA", "BALLET", "RODEO", "RACEWAY", "SPEEDWAY", priority = 3)

        // ------------------------------------------------------------------ Subscriptions
        word(
            "subscriptions", "NETFLIX", "SPOTIFY", "DISNEY PLUS", "DISNEYPLUS", "CRAVE", "ITUNES", "YOUTUBE", "PRIME VIDEO", "AUDIBLE", "PATREON",
            "OPENAI", "CHATGPT", "ANTHROPIC", "CLAUDE.AI", "ADOBE", "DROPBOX", "ICLOUD", "HBO", "PARAMOUNT+", "PARAMOUNT PLUS", "APPLE TV", "DAZN",
            "SPORTSNET", "TSN", "CRUNCHYROLL", "HULU", "PEACOCK", "SIRIUS", "SIRIUSXM", "DEEZER", "NORDVPN", "EXPRESSVPN", "1PASSWORD", "LASTPASS",
            "GITHUB", "NOTION", "CANVA", "LINKEDIN", "ZOOM.US", "DUOLINGO", "HEADSPACE", "CALM.COM", "NYTIMES", "NEW YORK TIMES",
            "GLOBE AND MAIL", "THE ATHLETIC", "SUBSTACK", "AMAZON WEB SERVICES", "AWS", "DIGITALOCEAN", "GODADDY", "NAMECHEAP", "SQUARESPACE",
            "WIX.COM", "TINDER", "BUMBLE", "HINGE", "KINDLE UNLIMITED", "GOOGLE ONE", "GOOGLE STORAGE", "APPLE MUSIC", "APPLE ONE",
            "AMAZON MUSIC", "TIDAL", "SOUNDCLOUD", "PANDORA PREMIUM", "DISCORD", "TELEGRAM PREMIUM", "X PREMIUM", "TWITTER BLUE",
            "MIDJOURNEY", "PERPLEXITY", "CURSOR", "COPILOT", "GEMINI ADVANCED", "GROK", "ELEVENLABS", "FIGMA", "GRAMMARLY",
            "EVERNOTE", "TODOIST", "OBSIDIAN", "BITWARDEN", "DASHLANE", "PROTON", "PROTONMAIL", "FASTMAIL", "SURFSHARK",
            "MULLVAD", "BACKBLAZE", "CLOUDFLARE", "HEROKU", "VERCEL", "NETLIFY", "LINODE", "HETZNER", "OVH", "JETBRAINS",
            "SETAPP", "PLEX", "MUBI", "CRITERION", "BRITBOX", "ACORN TV", "SHUDDER", "DISCOVERY+", "DISCOVERY PLUS", "CBC GEM",
            "CLUB ILLICO", "FUBO", "NBA LEAGUE PASS", "NHL LIVE", "MLB.TV", "F1 TV", "UFC FIGHT PASS", "WWE NETWORK",
            "KOBO PLUS", "SCRIBD", "BLINKIST", "MASTERCLASS", "BRILLIANT.ORG", "WSJ", "WALL STREET JOURNAL", "ECONOMIST",
            "WASHINGTON POST", "THE GUARDIAN", "TORONTO STAR", "VANCOUVER SUN", "NATIONAL POST", "THE TYEE", "MACLEANS",
            "MEDIUM.COM", "ONLYFANS", "BUMBLE", "MATCH.COM", "ANCESTRY", "23ANDME", "WEIGHT WATCHERS", "NOOM", "MYFITNESSPAL",
            "WHOOP", "OURA", "FITBIT PREMIUM", "GAIA GPS", "RING PROTECT", "NEST AWARE", "SIMPLISAFE",
            "COSTCO MEMBERSHIP", "AMAZON PRIME", "AMZN PRIME", "PRIME MEMBER", "WALMART+", "INSTACART+", "DASHPASS", "UBER ONE",
            "SKIP EXPRESS", "DROPBOX", "ONEDRIVE", "OFFICE 365", "MICROSOFT 365", "MSFT", "LINKTREE", "MAILCHIMP", "ZAPIER",
            "AIRTABLE", "TRELLO", "ASANA", "MONDAY.COM", "CLICKUP", "SLACK", "CALENDLY", "DOCUSIGN", "QUICKBOOKS", "FRESHBOOKS",
            "WAVE APPS", "XERO", "SHOPIFY SUBSCRIPTION", "ETSY SELLER", "PLAYSTATION PLUS", "PS PLUS", "XBOX GAME PASS",
            "GAME PASS", "NINTENDO ONLINE", "EA PLAY", "UBISOFT+", "APPLE ARCADE", "HUMBLE BUNDLE",
        )
        has("subscriptions", "APPLE.COM/BILL", "GOOGLE *", "GOOGLE*", priority = 10)
        word("subscriptions", "DISNEY", priority = 8)
        word("subscriptions", "MICROSOFT", "GOOGLE", "SUBSCRIPTION", "MEMBERSHIP", priority = 4)

        // ------------------------------------------------------------------ Gifts & donations
        word("gifts", "CANADAHELPS", "RED CROSS", "UNITED WAY", "UNICEF", "WORLD VISION", "SALVATION ARMY", "GOFUNDME", "HALLMARK", "CARLTON CARDS", "PURDYS",
            "DOCTORS WITHOUT BORDERS", "MSF", "SICKKIDS", "BC CHILDREN'S", "BC CANCER", "CANADIAN CANCER", "HEART & STROKE", "HEART AND STROKE", "SPCA",
            "FOOD BANK", "COVENANT HOUSE", "UNION GOSPEL", "HABITAT FOR HUMANITY", "WWF", "NATURE CONSERVANCY", "DAVID SUZUKI", "AMNESTY", "OXFAM",
            "PLAN CANADA", "TERRY FOX", "MOVEMBER", "WIKIMEDIA", "1-800-FLOWERS", "TELEFLORA", "FTD", "EDIBLE ARRANGEMENTS", "GIFT CARD", "GIFT SHOP")
        stem("gifts", "DONATION", "DONATE", "CHARIT", "FLORIST", "FLOWER", "FUNDRAIS", "TITHE", priority = 5)
        word("gifts", "GIFTS", "GIFT", priority = 4)
        word("gifts", "FOUNDATION", "CHURCH", "TEMPLE", "MOSQUE", "SYNAGOGUE", "PARISH", "MINISTRIES", priority = 3)

        // ------------------------------------------------------------------ Education
        word("education", "UBC", "SFU", "BCIT", "LANGARA", "DOUGLAS COLLEGE", "KPU", "KWANTLEN", "CAPILANO U", "UVIC", "COURSERA", "UDEMY", "SKILLSHARE",
            "NSLSC", "CANADA STUDENT LOAN", "STUDENTAID BC", "YOUNG DRIVERS", "DRIVING SCHOOL", "CHILD CARE", "VCC", "EMILY CARR", "UNBC", "UFV",
            "CAMOSUN", "OKANAGAN COLLEGE", "JUSTICE INSTITUTE", "U OF T", "MCGILL", "WATERLOO", "QUEEN'S U", "WESTERN U", "MCMASTER", "U OF A", "U OF C",
            "EDX", "PLURALSIGHT", "CODECADEMY", "DATACAMP", "KHAN ACADEMY", "KUMON", "SYLVAN", "OXFORD LEARNING", "MATHNASIUM", "PEARSON", "MCGRAW",
            "CENGAGE", "CHEGG", "COURSE HERO", "QUIZLET", "ROSETTA STONE", "BABBEL", "IELTS", "TOEFL", "LSAT", "MCAT", "GMAT", "CPA CANADA", "CFA INSTITUTE",
            "COMPTIA", "STUDENT LOAN", "ONTARIO STUDENT", "OSAP")
        stem("education", "TUITION", "TUTOR", "DAYCARE", "PRESCHOOL", "MONTESSORI", "TEXTBOOK", "KINDERGARTEN", "CHILDCARE", priority = 5)
        stem("education", "UNIVERSIT", "COLLEGE", "SCHOLAST", priority = 3)
        word("education", "SCHOOL", "ACADEMY", "LESSONS", "STUDENT", "EXAM", "LEARNING", "EDUCATION", priority = 4)

        // ------------------------------------------------------------------ Taxes & government
        word("government", "CANADA REVENUE", "REVENUE AGENCY", "RECEIVER GENERAL", "SERVICE CANADA", "SERVICE BC", "SERVICEBC", "PROVINCE OF BC", "IRCC", "TURBOTAX",
            "WEALTHSIMPLE TAX", "H&R BLOCK", "UFILE", "PROPERTY TAX", "INCOME TAX", "TAX PAYMENT", "BUSINESS TAXES", "DRIVER LICENSING", "DRIVER LICENCE",
            "VITAL STATISTICS", "LAND TITLE", "BC REGISTRY", "BC REGISTRIES", "COURT SERVICES", "PROVINCIAL COURT", "BYLAW", "CBSA", "CANADA BORDER",
            "GOV OF CANADA", "GOVERNMENT OF CANADA", "GOVERNMENT OF BC", "GOUV", "MINISTRY OF FINANCE", "MINISTER OF FINANCE", "BC MSP", "TAX INSTALMENT",
            "STUDIOTAX", "GENUTAX", "SIMPLETAX", "INTUIT", priority = 12)
        word("government", "CRA", priority = 12, sign = -1)
        stem("government", "PASSPORT", "IMMIGRATION", "CITIZENSHIP", "GOVERNMENT", priority = 6)
        word("government", "LICENCE", "LICENSE", "PERMIT", "PERMITS", "REGISTRY", "CONSULATE", "EMBASSY", "VISA APPLICATION", priority = 4)
        word("government", "TAX", "TAXES", priority = 3, sign = -1)

        // ------------------------------------------------------------------ Cash
        word("cash", "ATM", "ABM", "ABM WITHDRAWAL", "CASH WITHDRAWAL", "CASH ADVANCE", priority = 15)

        // ------------------------------------------------------------------ Fees & interest (money out)
        has("fees", "INTEREST CHARGE", "PURCHASE INTEREST", "CASH ADVANCE INTEREST", priority = 30, sign = -1)
        has("fees", "INTEREST ON", "OVERDRAFT INTEREST", priority = 25, sign = -1)
        has("fees", "ANNUAL FEE", "SERVICE CHARGE", "MONTHLY FEE", "ACCOUNT FEE", "OVERDRAFT", "FOREIGN TRANSACTION FEE", "FX FEE", "LATE PAYMENT FEE", "OVERLIMIT FEE", "OVER LIMIT FEE", "DISHONOURED", priority = 30)
        word("fees", "NSF", priority = 30)
        has("fees", "INTERAC E-TRANSFER FEE", "ABM FEE", "ATM FEE", priority = 35)
        word("fees", "FEE", "FEES", priority = 1, sign = -1)

        // ------------------------------------------------------------------ e-Transfers
        has("etransfer_sent", "E-TRANSFER", "ETRANSFER", "INTERAC E-TRF", "SEND E-TFR", "E-TFR", priority = 8, sign = -1)
        has("etransfer_sent", "INTERAC E-TRANSFER RTN", priority = 30)
        has("other_income", "E-TRANSFER", "ETRANSFER", "INTERAC E-TRF", "E-TFR", priority = 8, sign = 1)

        // ------------------------------------------------------------------ Income (money in)
        has("paycheque", "PAYROLL", "PAY DEPOSIT", "SALARY", priority = 20, sign = 1)
        has("paycheque", "DIRECT DEPOSIT", priority = 15, sign = 1)
        has("other_income", "CANADA FED", "EMPLOYMENT INSURANCE", "CANADA EI", "CANADA CARBON", "CLIMATE ACTION", "BC CLIMATE", "TAX REFUND", "CANADA WORKERS", "CANADA CHILD", "OLD AGE SECURITY", "CANADA PENSION", "BC FAMILY", "BC AFFORDABILITY", "GST/HST", priority = 30, sign = 1)
        word("other_income", "CRA", "GST", "CCB", "CAIP", "CPP", "OAS", "EI", priority = 12, sign = 1)
        has("investment_income", "INTEREST PAID", "INTEREST EARNED", "DIVIDEND", "BONUS INTEREST", priority = 20, sign = 1)
        has("refunds", "REFUND", "REBATE", "CASHBACK", "CASH BACK", priority = 15, sign = 1)
        has("refunds", "RETURN", "REVERSAL", priority = 4, sign = 1)

        // ------------------------------------------------------------------ Transfers between my accounts
        has("card_payment", "PAYMENT - THANK YOU", "PAYMENT THANK YOU", "PAYMENT RECEIVED", "PAYMENT-THANK YOU", priority = 40, sign = 1)
        has(
            "card_payment", "TRIANGLE MASTERCARD", "CANADIAN TIRE MC", "CTFS", "CANADIAN TIRE BANK", "SCOTIA VISA", "SCOTIABANK MASTERCARD",
            "CREDIT CARD/LOC PAYMENT", "SCOTIABANK VISA", "SCOTIA MOMENTUM", "CDN TIRE MC", "CANADIAN TIRE MASTERCARD", "TRIANGLE MC",
            priority = 40, sign = -1,
        )
        has("investments_transfer", "QUESTRADE", "IO TRANSFER TO INV", "WEALTHSIMPLE INVEST", "ITRADE", "SCOTIA ITRADE", "MUTUAL FUND", priority = 40)
        word("investments_transfer", "WEALTHSIMPLE", "TFSA", "RRSP", "FHSA", "RESP", "INVESTMENT", "INVESTMENTS", priority = 12)
        regex("between_accounts", "(?<!E-)\\bTRANSFER (TO|FROM)\\b", priority = 12)
        has("between_accounts", "TFR-TO", "TFR-FR", "MB-TRANSFER", "PC-TRANSFER", priority = 12)
    }

    val ALL: List<Rule> = rules.toList()
}
