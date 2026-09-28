import React from 'react';

type ChipType = 'object' | 'class' | 'datatype' | 'integrated';

interface OChipProps {
  label: string;
  type?: ChipType;
  sm?: boolean;
}

const chipStyles: Record<ChipType, string> = {
  object:
    'bg-blue-500/15 text-blue-400 border-blue-500/30',
  class:
    'bg-teal-500/15 text-teal-400 border-teal-500/30',
  datatype:
    'bg-amber-500/15 text-amber-400 border-amber-500/30',
  integrated:
    'bg-emerald-500/15 text-emerald-400 border-emerald-500/30',
};

export const OChip: React.FC<OChipProps> = ({
  label,
  type = 'object',
  sm = false,
}) => {
  return (
    <span
      className={`inline-flex items-center font-mono font-semibold uppercase tracking-wider border rounded-sm select-none ${chipStyles[type]} ${
        sm
          ? 'text-[8px] px-1 py-[1px] leading-tight'
          : 'text-[9px] px-1.5 py-[2px] leading-tight'
      }`}
    >
      {label}
    </span>
  );
};

export default OChip;
