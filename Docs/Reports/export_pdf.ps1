# Экспорт отчётов labN.docx в PDF через Microsoft Word (нужен установленный Word).
# Запуск из корня репозитория:  powershell -File Docs/Reports/export_pdf.ps1
# (если политика выполнения запрещает .ps1 — вставьте содержимое файла в окно PowerShell)
$dir = $PSScriptRoot
$job = Start-Job -ArgumentList $dir -ScriptBlock {
  param($dir)
  $word = New-Object -ComObject Word.Application
  $word.Visible = $false; $word.DisplayAlerts = 0
  try {
    Get-ChildItem $dir -Filter 'lab*.docx' | Sort-Object Name | ForEach-Object {
      $doc = $word.Documents.Open($_.FullName, $false, $true, $false)
      $pdf = [IO.Path]::ChangeExtension($_.FullName, '.pdf')
      $doc.ExportAsFixedFormat($pdf, 17)
      "$($_.BaseName).pdf — страниц: $($doc.ComputeStatistics(2))"
      $doc.Close($false)
    }
  } finally { $word.Quit() }
}
# Если Word показал скрытый диалог первого запуска — не ждём бесконечно.
if (Wait-Job $job -Timeout 300) { Receive-Job $job } else { 'Word не ответил за 5 минут — откройте Word вручную один раз и повторите'; Stop-Job $job }
Remove-Job $job -Force
