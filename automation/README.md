# Aggiornamenti autonomi del Laboratorio

Il controllo è eseguito da GitHub Actions ogni sei ore. Non dipende da ChatGPT, dal PC acceso o da un promemoria. La pubblicazione automatica richiede la configurazione iniziale della firma descritta sotto. Senza chiave, il servizio registra `signing_not_configured` e conserva il catalogo corrente.

## Flusso

1. `automatic-upstream.yml` legge la release stabile ufficiale più recente di `magefree/mage`, esclude draft e prerelease e verifica tag, commit, asset e SHA-256. Il catalogo firmato determina la versione distribuita; `request.json` rimane una traccia storica, non richiede più modifiche per ogni release.
2. Se la base ufficiale è più recente, integra le personalizzazioni di Lab 10.0 / IA6 con un merge a tre vie. Conflitti, file rimossi o strutture sconosciute interrompono il lavoro.
3. Compila contro il runtime ufficiale verificato, esegue almeno 147 test attivi e controlla installazione, backup, avvio del server e ritorno alla versione precedente in una copia isolata.
4. Un job separato pubblica il candidato immutabile `lab-auto-u<release-id>`. Il processo di compilazione e test non riceve la chiave o credenziali di scrittura.
5. Alla conclusione, `publish-channel.yml` controlla repository, ramo `main`, percorso del workflow, commit e risultato. `channel.py` esegue prima `promote.py` senza chiave: verifica la provenienza del candidato, la release ufficiale, il pacchetto riscaricato e l'intero inventario.
6. Solo dopo questa verifica, la fase di firma usa il secret dell'ambiente `lab-release`. La chiave viene scritta in una cartella temporanea privata, con permessi 600, verificata contro la chiave pubblica incorporata nel launcher e rimossa anche in caso di errore.
7. Il nuovo catalogo, il precedente archiviato, la prova e lo stato vengono pubblicati in un unico commit. La testa di `main` e l'hash del catalogo vengono ricontrollati; il riferimento viene aggiornato senza force. Una modifica concorrente interrompe l'operazione e il controllo successivo riparte dal nuovo stato.
8. Il catalogo viene scaricato dall'indirizzo effettivo del launcher e la firma, il seriale e il contenuto vengono verificati nuovamente.

Replay, downgrade, prove incoerenti, pacchetti alterati o archivi già pubblicati con contenuto differente sono rifiutati. I test e le personalizzazioni non vengono indeboliti per superare un blocco. Un errore mantiene disponibile l'ultima versione verificata; il controllo successivo può ritentare. I problemi di compatibilità richiedono una correzione di sviluppo.

## Configurazione iniziale della firma

Da **Settings → Environments** del repository:

1. Crea o apri l'ambiente **`lab-release`**.
2. In **Deployment branches and tags**, seleziona i rami/tag specifici e consenti esclusivamente il **branch `main`**. Non aggiungere una regola per i tag.
3. Per la pubblicazione autonoma, l'ambiente non deve richiedere un'approvazione manuale per ogni esecuzione. Non rimuovere protezioni già impostate senza una decisione esplicita del proprietario.
4. Aggiungi come **Environment secret**, non come file del repository, **`LAB_CATALOG_PRIVATE_KEY`**: il contenuto PEM completo della chiave originale nell'archivio privato `Materiale_firma_Laboratorio_PRIVATO.zip`. Non generare una nuova chiave: il launcher riconosce quella già incorporata.
5. In **Actions → Automatic official XMage integration → Run workflow**, scegli `main`. Dopo il controllo, anche **Publish verified Laboratorio channel** deve riuscire. Se non c'è una nuova release, il catalogo resta uguale e lo stato passa a `current` solo se la chiave configurata corrisponde a quella del launcher.

La chiave resta un segreto custodito da GitHub per la sola fase di firma. Non deve essere incollata in chat, commit, issue, note di release, log o artifact. Non è usata per scaricare o eseguire codice ufficiale. Chi può modificare i workflow su `main` deve essere considerato affidabile; proteggere gli accessi al repository.

## Uso nell'app

Il launcher 0.3.1 continua a scaricare i pacchetti dal catalogo esistente. Il launcher **0.3.2** aggiunge, in **Aggiornamenti**, la sezione **Preparazione automatica degli aggiornamenti** con la data dell'ultimo controllo e l'eventuale blocco. Dopo 72 ore senza controlli registrati mostra un avviso. Lo stato è solo informativo: soltanto il catalogo firmato autorizza il download/installazione.

L'utente preme **Controlla aggiornamenti**, poi **Scarica e aggiorna Laboratorio**. L'installazione sul PC resta manuale; il servizio non interrompe partite. Mazzi, report e registro condiviso non fanno parte di queste operazioni. Non vengono creati promemoria o notifiche ChatGPT.

Il controllo del servizio avviene con il pulsante dell'app. All'apertura viene mostrato l'ultimo stato salvato, con la sua data; in assenza di rete rimane consultabile. Il nuovo launcher si installa una volta sostituendo `launcher/laboratorio-launcher.jar` a launcher, client e server chiusi, come indicato nello ZIP.

## Stato, limiti e verifiche

`updates/automation-state.json` registra lo stato del servizio e la data di ogni controllo con un piccolo commit, anche se il catalogo non cambia. Non contiene dati personali né chiavi. Gli aggiornamenti del solo stato non attivano un'altra integrazione. I risultati di run precedenti non sovrascrivono quelli di run più recenti.

Gli orari di GitHub Actions possono subire ritardi; GitHub può disattivare workflow programmati in repository pubblici inattivi. La data nell'app rende visibile un eventuale arresto. Se il workflow di pubblicazione non parte per un blocco dell'ambiente o dei permessi, lo stato diventa obsoleto e il launcher lo segnala. Le preferenze personali di notifica di GitHub rimangono gestite dall'account GitHub.

```sh
python3 -m unittest discover -s automation/tests -v
python3 automation/build_launcher.py --output launcher-build
python3 automation/pipeline.py --replay --work auto-work
```

`--replay` ripete anche compilazione, test e verifica installazione sulla release corrente, senza promuoverla come nuova. Le verifiche automatiche non equivalgono a una partita grafica completa su Windows.

Per una promozione manuale di emergenza, da un checkout aggiornato:

```sh
python3 automation/promote.py --tag lab-auto-u<release-id> --work promotion-work
python3 automation/promote.py --tag lab-auto-u<release-id> --work promotion-work --key /percorso/privato/chiave.pem
```

Questi due comandi preparano i file senza scrivere su GitHub. La pubblicazione deve mantenere le stesse verifiche sul ramo e su `previousCatalogSha256`, la scrittura atomica di catalogo/archivio/prova/stato e il controllo finale dell'URL del launcher.
