# XMage Laboratorio 10.0 / IA6

Integrazione della release ufficiale XMage 1.4.62-V1 del 3 ottobre 2026.

Il pacchetto mantiene registrazione e analisi Laboratorio, ricerca IA6 con budget massimo 10/5000/30 secondi, correzione delle sequenze finite e conferma esplicita dei pareggi, protezione dei rollback oltre un'uscita.

Le modifiche sono in `overlay/`, sopra il commit ufficiale `eb69d05b88142b5c4eec4869bcb23a0fec13c091`. Gli archivi ufficiali e JUnit sono verificati tramite SHA-256. `ci.py` ricompila con JDK 17 e bytecode Java 8, confronta ogni classe di produzione con quella verificata localmente ed esegue la selezione pubblica dei test. Il pacchetto usa la distribuzione ufficiale come base: non è una build Maven completa.

Verifica locale: 450 test superati, 14 disabilitati, 3 interrotti perché richiedono archivi storici esterni, nessun fallimento. Installazione del pacchetto, backup, avvio server Linux e ritorno alla versione precedente conservando i dati successivi verificati. Nessuna prova grafica o partita completa Windows ancora attestata. Questi controlli non misurano la forza strategica generale dell'IA.

Il flusso pubblico pubblica il pacchetto solo dopo i controlli. Il catalogo firmato viene pubblicato separatamente dopo la verifica del download reale. La chiave di firma, i report personali e le liste dei mazzi non sono presenti nel repository.

Per ricostruire: dalla radice di questo repository, con Python 3.9+ e JDK 17 nel PATH, eseguire `python releases/lab-10.0-ai6/ci.py`.
