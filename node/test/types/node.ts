import * as hypaxel from 'hypaxel'

const workbook: hypaxel.Workbook = hypaxel.openWorkbookSync(new Uint8Array())
const options: hypaxel.ReadOptions = { maxTables: 1, maxTableBytes: 1, maxTokenBytes: 1, maxRowTextBytes: 1 }
void workbook
void options
