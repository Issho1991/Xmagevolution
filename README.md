# Xmagevolution — XMage Laboratorio

Personalizzazione di XMage per il gioco e l'analisi delle partite.

## Aggiornamento dal Laboratorio

Disponibile **Laboratorio 10.0 / IA6**, basato su **XMage 1.4.62-V1**.

Con il launcher 0.3.1 già installato:

1. Termina la partita e chiudi client e server XMage; lascia aperto il launcher.
2. Apri **Aggiornamenti → Imposta canale…**.
3. Incolla questo indirizzo e conferma:

   `https://raw.githubusercontent.com/Issho1991/Xmagevolution/main/updates/catalog.properties`

4. Premi **Controlla aggiornamenti**.
5. Quando compare **Lab 10.0 · IA 6 · base XMage 1.4.62-V1**, premi **Scarica e aggiorna Laboratorio** e conferma.
6. Attendi il messaggio **Aggiornamento attivato**, poi avvia il Laboratorio.

Il canale va impostato una sola volta. Il launcher verifica firma e integrità del download, crea un backup e conserva mazzi e report. Le versioni precedenti restano selezionabili in **Versioni e backup**.

[Release e note](https://github.com/Issho1991/Xmagevolution/releases/tag/lab-10.0-ai6) · [Verifica automatica](https://github.com/Issho1991/Xmagevolution/actions/runs/38600772837) · [Resoconto di verifica](updates/lab-10.0-verification.json)

## Integrazione degli aggiornamenti ufficiali

Il launcher controlla separatamente gli aggiornamenti ufficiali XMage e i pacchetti Laboratorio. È predisposto il [flusso automatico di integrazione](automation/README.md): un controllo programmato rileva le nuove release, GitHub integra e verifica il pacchetto e una verifica separata aggiorna il catalogo firmato. Conflitti o verifiche fallite mantengono disponibile la versione precedente. Sul PC l'installazione si avvia dallo stesso pulsante **Scarica e aggiorna Laboratorio**.

Per Lab 10.0 sono passati 450 test locali; la build pubblica ha ripetuto 147 test attivi e confrontato tutte le classi di produzione. Il pacchetto scaricato dalla release coincide con quello verificato localmente. Una partita grafica completa su Windows non fa parte di queste verifiche.

Il catalogo è firmato e identifica il pacchetto tramite SHA-256. La chiave privata di firma resta esterna al repository. Mazzi personali, report delle partite e registri privati non fanno parte della distribuzione pubblica.

## Progetto originale

XMage: https://github.com/magefree/mage

Questa distribuzione personalizzata non è una release ufficiale del progetto XMage.
