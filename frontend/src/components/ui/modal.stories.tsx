import type { Meta, StoryObj } from '@storybook/react-vite'
import { Modal, ModalContent, ModalHeader, ModalTitle, ModalDescription, ModalFooter, ModalTrigger, ModalClose } from './modal'
import { Button } from './button'

const meta: Meta<typeof Modal> = {
  title: 'UI/Modal',
  tags: ['autodocs'],
}
export default meta

type Story = StoryObj<typeof Modal>

export const CloseTicketConfirm: Story = {
  render: () => (
    <Modal>
      <ModalTrigger asChild>
        <Button>Open modal</Button>
      </ModalTrigger>
      <ModalContent>
        <ModalHeader>
          <ModalTitle>Close ticket</ModalTitle>
          <ModalDescription>This action seals the current cycle. It cannot be undone.</ModalDescription>
        </ModalHeader>
        <p className="text-sm text-content">Resolution summary, root cause and final effort are confirmed on the previous screen.</p>
        <ModalFooter>
          <ModalClose asChild>
            <Button variant="secondary">Cancel</Button>
          </ModalClose>
          <Button>Confirm</Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  ),
}
