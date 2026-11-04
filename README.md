# Diagnosi cotxe

App petita per saber què deixa fer la unitat Android del cotxe: quines tecles del volant arriben a una app, què diu el GPS, quines apps hi ha instal·lades i quins sensors té.

## Com compilar-la amb GitHub (sense Android Studio)

1. Descomprimeix el zip.
2. A github.com, crea un repositori nou: nom `diagnosi-cotxe`, **públic**.
3. Al repositori, tria **Add file > Upload files** i arrossega-hi **el contingut** de la carpeta (no la carpeta mateixa). Prem **Commit changes**.
   - La carpeta `.github` és oculta en alguns sistemes. Si no et surt, crea el fitxer a mà: **Add file > Create new file**, escriu al nom `.github/workflows/build.yml` (les barres creen les carpetes) i enganxa-hi el contingut de `COPIA-workflow-build.yml`.
4. Obre la pestanya **Actions**. Hi veuràs una execució en marxa; tarda entre 3 i 8 minuts. Si surt en verd, ha anat bé. Si surt en vermell, obre-la i copia'm l'error.
5. Per descarregar l'APK, a la pàgina principal del repositori, a la dreta, entra a **Releases > Ultima versio** i baixa `app-debug.apk`. Es pot fer directament des del navegador de la unitat.
6. Instal·la'l (el navegador et demanarà permís per instal·lar apps d'orígens desconeguts).

## Com fer-la servir

Obre **Diagnosi cotxe** i accepta el permís d'ubicació. Té quatre pestanyes:

- **Tecles**: prem els botons del volant (volum, track+, track-, mode) i els de la unitat. Cada pulsació queda registrada amb el seu codi. Això ens diu si arriben a una app externa.
- **GPS**: velocitat, rumb, alçada, satèl·lits i si la unitat té brúixola real.
- **Apps**: totes les apps amb icona, amb el seu nom de paquet (ens servirà per obrir telèfon i ràdio des del llançador).
- **Sistema**: pantalla, sensors i dispositius d'entrada.

Els botons **Copia** i **Comparteix** recullen tot l'informe en un text. Si no pots enviar-lo, fes captures de cada pestanya.
