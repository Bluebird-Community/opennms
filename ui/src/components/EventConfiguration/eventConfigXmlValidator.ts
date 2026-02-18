import { UploadEventFileType } from '@/types/eventConfig'
import { XMLValidator } from 'fast-xml-parser'

export const MAX_FILES_UPLOAD = 10

export const validateEventConfigFile = async (file: File) => {
  const validationErrors: string[] = []

  try {
    const text = await file.text()

    if (text.trim().length === 0) {
      validationErrors.push('File is empty')
      return { isValid: false, errors: validationErrors }
    }

    if (!file.name.endsWith('.xml') && !file.name.includes('event')) {
      validationErrors.push('File does not appear to be an event configuration file (expected .xml extension)')
      return { isValid: false, errors: validationErrors }
    }

    if (validationErrors.length === 0) {
      let parser: any
      try {
        parser = new (DOMParser as any)()
      } catch (e) {
        parser = (DOMParser as any)()
      }
      const xmlDoc = parser.parseFromString(text, 'application/xml')
      const result = XMLValidator.validate(text)
      if (xmlDoc.querySelector('parsererror')) {
        validationErrors.push('Invalid XML format - file contains syntax errors')
        return { isValid: false, errors: validationErrors }
      }
      if (result !== true) {
        validationErrors.push('Invalid XML format - file contains syntax errors')
        return { isValid: false, errors: validationErrors }
      }

      const eventsElement = xmlDoc.querySelector('events')
      if (!eventsElement) {
        validationErrors.push('Missing <events> root element')
        return { isValid: false, errors: validationErrors }
      }
      const xmlns = eventsElement.getAttribute('xmlns') || ''
      if (xmlns !== 'http://xmlns.opennms.org/xsd/eventconf') {
        validationErrors.push('Missing or invalid OpenNMS namespace in <events> element')
        return { isValid: false, errors: validationErrors }
      }

      const eventElements = eventsElement.querySelectorAll('event')
      const childElements = eventsElement.children
      if (childElements.length && eventElements.length === 0) {
        const childNames = Array.from(childElements as any[])
          .map((el: any) => `<${String(el.tagName).toLowerCase()}>`)
          .join(', ')
        validationErrors.push(`<events> element contains ${childNames} but no <event> elements`)
        return { isValid: false, errors: validationErrors }
      } else if (eventElements.length === 0) {
        validationErrors.push('No <event> entries found within <events> element')
        return { isValid: false, errors: validationErrors }
      } else {
        try {
          const eventList = Array.from(eventElements as any[]) as Element[]
          for (const [idx, event] of eventList.entries()) {
            const eventErrors = validateEventElement(event as any, idx + 1)
            if (eventErrors) {
              validationErrors.push(eventErrors)
              return { isValid: false, errors: validationErrors }
            }
          }
        } catch (error) {
          validationErrors.push(
            `Error reading file content: ${error instanceof Error ? error.message : 'Unknown error'}`
          )
          return { isValid: false, errors: validationErrors }
        }
      }
    }
  } catch (error) {
    validationErrors.push(`Error reading file content: ${error instanceof Error ? error.message : 'Unknown error'}`)
    return { isValid: false, errors: validationErrors }
  }
  return {
    isValid: validationErrors.length === 0,
    errors: validationErrors
  }
}

// Helper function to extract inner text from XML element
const getInnerText = (el: Element, tag: string): string => {
  if (!el) return ''
  const node = el.querySelector(tag) || el.getElementsByTagName(tag)[0]
  return node?.textContent?.trim() || ''
}

export const validateEventElement = (event: Element, eventNumber: number): string => {
  const uei = event.querySelector('uei')?.textContent?.trim()
  const label = event.querySelector('event-label')?.textContent?.trim()
  const severity = event.querySelector('severity')?.textContent?.trim()
  const description = event.querySelector('descr')?.textContent?.trim()

  if (!uei) {
    return `Event ${eventNumber}: missing <uei>`
  }
  if (!label) {
    return `Event ${eventNumber}: missing <event-label>`
  }
  if (!severity) {
    return `Event ${eventNumber}: missing <severity>`
  }
  if (!description) {
    return `Event ${eventNumber}: missing <descr>`
  }

  return ''
}

export const isDuplicateFile = (fileName: string, existingFiles: UploadEventFileType[]): boolean => {
  return !!existingFiles?.some((element) => element.file.name.toLowerCase() === fileName.toLowerCase())
}

